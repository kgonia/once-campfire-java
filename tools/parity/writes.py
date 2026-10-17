"""Write-path parity: submits the same browser forms to Rust (4100) and Java (4101) and compares what each
write does.

Usage: python writes.py [--only SUBSTR] [--show N]

Both servers must have just been started on fresh copies of the same seed (`up.sh`, or a Java image with
its data on a Docker volume), with the shared signed cookie in $PARITY_WORK/cookie.txt. Cases run in order
against both, so later cases see earlier writes. For each case it compares:

- the status, the redirect's path and query, and which cookies were set (by name);
- JSON and Turbo Stream bodies, with times, signed ids and tokens normalized;
- the database effect, the main check: which rows each write added, removed or changed, table by table.
  Time columns compare as "changed or not", random ones (tokens, keys, digests) the same way.

The databases are read from inside the containers (`docker cp`), so it works whether a server keeps its
data in a bind mount or a volume. Container names come from RUST_CONTAINER / JAVA_CONTAINER.
"""
import argparse, difflib, http.client, json, os, re, shutil, sqlite3, subprocess, tempfile, time, urllib.parse, uuid, zlib, struct
import markup

ROOT = os.environ.get('PARITY_WORK', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'work'))
L = json.load(open(os.path.join(os.environ['CAMPFIRE_SEED'], 'labels.json'), encoding='utf-8'))
SIDES = {'rust': (4100, os.environ.get('RUST_CONTAINER', 'cf-rust-parity')),
         'java': (4101, os.environ.get('JAVA_CONTAINER', 'cf-java-parity'))}
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
TURBO = 'text/vnd.turbo-stream.html, text/html, application/xhtml+xml'
TABLES = ['accounts', 'users', 'sessions', 'rooms', 'memberships', 'messages', 'action_text_rich_texts', 'boosts', 'searches',
          'bans', 'webhooks', 'push_subscriptions', 'active_storage_blobs', 'active_storage_attachments',
          'active_storage_variant_records']
RANDOM = {'token', 'bot_token', 'join_code', 'key', 'password_digest'}
TIME = re.compile(r'(_at|_on)$')
# Server-generated UUIDs (client message ids of bot posts, deactivated users' emails); ours say "parity".
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}')


# -- HTTP ------------------------------------------------------------------------------------------

def png(width=4, height=3):
    """A small valid PNG, so uploads go through the same analysis on both sides."""
    raw = b''.join(b'\x00' + b'\xff\x40\x20' * width for _ in range(height))
    chunk = lambda tag, data: struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def multipart(fields, files):
    boundary = '----parity' + uuid.uuid4().hex
    out = []
    for name, value in fields:
        out.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    for name, filename, ctype, data in files:
        out.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                   f'Content-Type: {ctype}\r\n\r\n'.encode() + data + b'\r\n')
    out.append(f'--{boundary}--\r\n'.encode())
    return b''.join(out), f'multipart/form-data; boundary={boundary}'


class Side:
    def __init__(self, name):
        self.name = name
        self.port, self.container = SIDES[name]
        self.jar = dict(c.split('=', 1) for c in open(os.path.join(ROOT, 'cookie.txt')).read().strip().split('; '))
        self.db = None

    def request(self, case):
        headers = {'User-Agent': UA, 'Accept': case.get('accept', TURBO), 'Sec-Fetch-Site': 'same-origin'}
        jar = {} if case.get('anonymous') else self.jar
        if jar:
            headers['Cookie'] = '; '.join(f'{k}={v}' for k, v in jar.items())
        body = None
        if case.get('files') is not None:
            body, headers['Content-Type'] = multipart(case.get('form', []), case['files'])
        elif case.get('json') is not None:
            body, headers['Content-Type'] = json.dumps(case['json']).encode(), 'application/json'
        elif case.get('raw') is not None:
            body, headers['Content-Type'] = case['raw'].encode(), 'text/plain'
        elif case.get('form') is not None:
            body, headers['Content-Type'] = urllib.parse.urlencode(case['form']).encode(), 'application/x-www-form-urlencoded'
        path = case['path'](self) if callable(case['path']) else case['path']
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=60)
        conn.request(case.get('method', 'POST'), path, body=body, headers=headers)
        r = conn.getresponse()
        data = r.read()
        hs = r.getheaders()
        conn.close()
        cookies = []
        for k, v in hs:
            if k.lower() != 'set-cookie':
                continue
            name, _, rest = v.partition('=')
            value = rest.split(';', 1)[0]
            cookies.append(name)
            if not case.get('anonymous'):
                if value and 'max-age=0' not in v.lower() and '1970' not in v:
                    self.jar[name] = value
                else:
                    self.jar.pop(name, None)
        return r.status, dict((k.lower(), v) for k, v in hs), sorted(set(cookies)), data

    def snapshot(self):
        tmp = tempfile.mkdtemp(prefix='writes-')
        try:
            subprocess.run(['docker', 'cp', f'{self.container}:/rails/storage/db/.', tmp], check=True, capture_output=True)
            for f in os.listdir(tmp):
                if f.endswith('-shm'):
                    os.remove(os.path.join(tmp, f))
            db = sqlite3.connect(os.path.join(tmp, 'production.sqlite3'))
            snap = {}
            for t in TABLES:
                cur = db.execute(f'select * from "{t}"')
                cols = [c[0] for c in cur.description]
                key = 'id' if 'id' in cols else cols[0]
                snap[t] = {row[cols.index(key)]: dict(zip(cols, row)) for row in cur}
            snap['message_search_index'] = {r[0]: {'rowid': r[0], 'body': r[1]} for r in db.execute('select rowid, body from message_search_index')}
            db.close()
            self.db = snap
            return snap
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def find(self, table, **where):
        """The newest row id in the last snapshot whose columns equal `where`."""
        ids = [k for k, row in self.db[table].items() if all(row.get(c) == v for c, v in where.items())]
        return max(ids) if ids else 0


# -- Comparison ------------------------------------------------------------------------------------

def shown(col, value):
    if value is None:
        return None
    if col in RANDOM:
        return '<random>'
    if TIME.search(col):
        return '<time>'
    if isinstance(value, str):
        return UUID.sub('<uuid>', value)
    return value


def delta(before, after):
    out = {}
    for table in after:
        b, a = before.get(table, {}), after[table]
        added = {k: {c: shown(c, v) for c, v in row.items()} for k, row in a.items() if k not in b}
        removed = sorted(k for k in b if k not in a)
        changed = {}
        for k in a.keys() & b.keys():
            cols = {c: ('changed' if c in RANDOM or TIME.search(c) else [shown(c, b[k].get(c)), shown(c, v)])
                    for c, v in a[k].items() if b[k].get(c) != v}
            if cols:
                changed[k] = cols
        if added or removed or changed:
            out[table] = {'added': added, 'removed': removed, 'changed': changed}
    return out


def norm_body(ctype, data):
    s = data.decode('utf-8', 'replace')
    if 'json' in ctype:
        try:
            s = json.dumps(json.loads(s), sort_keys=True, indent=1, ensure_ascii=False)
        except ValueError:
            pass
    s = s.replace(':4100', ':PORT').replace(':4101', ':PORT')
    s = re.sub(r'="\d{13}"', '="<ms>"', s)
    s = UUID.sub('<uuid>', s)
    s = re.sub(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d\d:?\d\d)?', '<time>', s)
    s = re.sub(r'(signed[-_]stream[-_]name|sgid|signed_id)(=|":\s*)"[^"]*"', r'\1\2"<signed>"', s)
    s = re.sub(r'/rails/active_storage/(blobs|representations)/(redirect/|proxy/)?[A-Za-z0-9=_-]+(--[0-9a-f]+)?', r'/rails/active_storage/\1/<signed>', s)
    s = re.sub(r'/session/transfers/[A-Za-z0-9=_-]+(--[0-9a-f]+)?', '/session/transfers/<signed>', s)
    if 'json' not in ctype and markup.looks_like_markup(s):
        return markup.tokens(s)
    return [line.strip() for line in re.sub(r'>\s*<', '>\n<', s).splitlines() if line.strip()]


def location(headers):
    loc = headers.get('location')
    if not loc:
        return None
    u = urllib.parse.urlsplit(loc)
    return u.path + ('?' + u.query if u.query else '')


# -- Cases -----------------------------------------------------------------------------------------

def cases():
    room = lambda k: L['rooms.' + k]
    user = lambda k: L['users.' + k]
    msg = lambda k: L['messages.' + k]
    wc, designers, bm, plain = room('watercooler'), room('designers'), msg('boosted_many'), msg('plain')
    jason, kevin, jz = user('jason'), user('kevin'), user('jz')
    client_id = '7d2a9c1e-0b5f-4c3a-9e6d-parity000001'
    endpoint = 'https://fcm.googleapis.com/fcm/send/parity'
    p256dh = 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4'
    image = ('image.png', 'image/png', png())
    return [
        dict(name='message create', path=f'/rooms/{wc}/messages',
             form=[('message[body]', '<p>Write parity hello</p>'), ('message[client_message_id]', client_id)]),
        dict(name='message create with attachment', path=f'/rooms/{wc}/messages', settle=4,
             form=[('message[client_message_id]', client_id.replace('1', '2'))], files=[('message[attachment]', *image)]),
        dict(name='message update', path=f'/rooms/{designers}/messages/{plain}',
             form=[('_method', 'patch'), ('message[body]', '<p>Edited by write parity</p>')]),
        dict(name='boost create', path=f'/messages/{bm}/boosts', form=[('boost[content]', '👍')]),
        dict(name='boost destroy', path=lambda s: f'/messages/{bm}/boosts/{s.find("boosts", message_id=bm, content="👍")}',
             form=[('_method', 'delete')]),
        dict(name='message destroy', path=lambda s: f'/rooms/{wc}/messages/{s.find("messages", client_message_id=client_id)}',
             form=[('_method', 'delete')]),
        dict(name='open room create', path='/rooms/opens', accept='text/html', form=[('room[name]', 'Parity Open')]),
        dict(name='open room rename', path=lambda s: f'/rooms/opens/{s.find("rooms", name="Parity Open")}', accept='text/html',
             form=[('_method', 'patch'), ('room[name]', 'Parity Open Renamed')]),
        dict(name='closed room create', path='/rooms/closeds', accept='text/html',
             form=[('room[name]', 'Parity Closed'), ('user_ids[]', str(L['users.david'])), ('user_ids[]', str(jason))]),
        dict(name='closed room update', path=lambda s: f'/rooms/closeds/{s.find("rooms", name="Parity Closed")}', accept='text/html',
             form=[('_method', 'patch'), ('room[name]', 'Parity Closed'), ('user_ids[]', str(L['users.david'])), ('user_ids[]', str(kevin))]),
        dict(name='involvement change', path=f'/rooms/{wc}/involvement?involvement=nothing', form=[('_method', 'put')]),
        dict(name='room destroy', path=lambda s: f'/rooms/{s.find("rooms", name="Parity Open Renamed")}', accept='text/html',
             form=[('_method', 'delete')]),
        dict(name='direct room create', path='/rooms/directs', accept='text/html', form=[('user_ids[]', str(jz))]),
        dict(name='profile update', path='/users/me/profile', accept='text/html',
             form=[('_method', 'patch'), ('user[name]', 'David Parity'), ('user[email_address]', L['emails.david']),
                   ('user[password]', ''), ('user[bio]', 'Writes parity checks')]),
        dict(name='avatar upload', path='/users/me/profile', accept='text/html', settle=3,
             form=[('_method', 'patch')], files=[('user[avatar]', *image)]),
        dict(name='avatar destroy', path=f'/users/{L["users.david"]}/avatar', accept='text/html', form=[('_method', 'delete')]),
        dict(name='account rename', path='/account', accept='text/html', form=[('_method', 'patch'), ('account[name]', 'Parity Co')]),
        dict(name='account settings', path='/account', accept='text/html',
             form=[('_method', 'put'), ('account[settings][restrict_room_creation_to_administrators]', 'true')]),
        dict(name='custom styles', path='/account/custom_styles', accept='text/html',
             form=[('_method', 'patch'), ('account[custom_styles]', 'body { color: rebeccapurple; }')]),
        dict(name='logo upload', path='/account', accept='text/html', settle=3, form=[('_method', 'patch')],
             files=[('account[logo]', *image)]),
        dict(name='logo destroy', path='/account/logo', accept='text/html', form=[('_method', 'delete')]),
        dict(name='join code reset', path='/account/join_code', accept='text/html', form=[]),
        dict(name='user role change', path=f'/account/users/{kevin}', accept='text/html',
             form=[('_method', 'patch'), ('user[role]', 'member'), ('user[role]', 'administrator')]),
        dict(name='bot create', path='/account/bots', accept='text/html',
             form=[('user[name]', 'Parity Bot'), ('user[webhook_url]', 'http://127.0.0.1:9/parity-hook')], files=[]),
        dict(name='bot update', path=lambda s: f'/account/bots/{s.find("users", name="Parity Bot")}', accept='text/html',
             form=[('_method', 'patch'), ('user[name]', 'Parity Bot'), ('user[webhook_url]', 'http://127.0.0.1:9/parity-hook-2')], files=[]),
        dict(name='bot key reset', path=lambda s: f'/account/bots/{s.find("users", name="Parity Bot")}/key', accept='text/html',
             form=[('_method', 'put')]),
        dict(name='bot API message', path=f'/rooms/{wc}/{L["bot_keys.bender"]}/messages', accept='*/*', raw='Parity bot says hello'),
        dict(name='bot API attachment', path=f'/rooms/{wc}/{L["bot_keys.bender"]}/messages', accept='*/*', settle=4,
             files=[('attachment', *image)]),
        dict(name='bot destroy', path=lambda s: f'/account/bots/{s.find("users", name="Parity Bot")}', accept='text/html',
             form=[('_method', 'delete')]),
        dict(name='push subscription create', path='/users/me/push_subscriptions', accept='*/*',
             json={'push_subscription': {'endpoint': endpoint, 'p256dh_key': p256dh, 'auth_key': 'BTBZMqHH6r4Tts7J_aSIgg'}}),
        dict(name='push subscription destroy', accept='text/html',
             path=lambda s: f'/users/me/push_subscriptions/{s.find("push_subscriptions", endpoint=endpoint)}', form=[('_method', 'delete')]),
        dict(name='search', path='/searches', accept='text/html', form=[('q', 'coffee')]),
        dict(name='search history clear', path='/searches/clear', accept='text/html', form=[('_method', 'delete')]),
        dict(name='ban user', path=f'/users/{kevin}/ban', accept='text/html', form=[]),
        dict(name='unban user', path=f'/users/{kevin}/ban', accept='text/html', form=[('_method', 'delete')]),
        dict(name='deactivate user', path=f'/account/users/{user("loner") if "users.loner" in L else jz}', accept='text/html',
             form=[('_method', 'delete')]),
        dict(name='join with code', anonymous=True, accept='text/html', settle=1,
             path=lambda s: f'/join/{next(iter(s.db["accounts"].values()))["join_code"]}',
             form=[('user[name]', 'Parity Joiner'), ('user[email_address]', 'joiner@example.com'), ('user[password]', 'parity-secret-1')],
             files=[]),
        dict(name='sign out', path='/session', accept='text/html', form=[('_method', 'delete'), ('push_subscription_endpoint', '')]),
        dict(name='sign in', path='/session', accept='text/html',
             form=[('email_address', L['emails.david']), ('password', L['passwords.all'])]),
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--only', default='')
    ap.add_argument('--show', type=int, default=30)
    args = ap.parse_args()
    sides = [Side('rust'), Side('java')]
    # One page view first, so both resume the shared session (activity, re-signed cookie) the same
    # way however the servers were used before.
    for s in sides:
        s.request(dict(name='warm-up', method='GET', path='/', accept='text/html'))
        s.snapshot()
    ok = diffs = 0
    for case in cases():
        if args.only and args.only not in case['name']:
            continue
        before = {s.name: s.db for s in sides}
        results = {s.name: s.request(case) for s in sides}
        time.sleep(case.get('settle', 0.5))
        effects = {s.name: delta(before[s.name], s.snapshot()) for s in sides}
        problems = []
        (rs, rh, rc, rb), (js, jh, jc, jb) = results['rust'], results['java']
        if rs != js:
            problems.append(f'status rust={rs} java={js}')
        if location(rh) != location(jh):
            problems.append(f'location rust={location(rh)} java={location(jh)}')
        if rc != jc:
            problems.append(f'cookies rust={rc} java={jc}')
        rt, jt = rh.get('content-type', '').split(';')[0], jh.get('content-type', '').split(';')[0]
        if rs == js and rs < 300 and (('json' in rt or 'turbo-stream' in rt) or ('json' in jt or 'turbo-stream' in jt)):
            if rt != jt:
                problems.append(f'content-type rust={rt} java={jt}')
            else:
                a, b = norm_body(rt, rb), norm_body(jt, jb)
                if a != b:
                    problems.append('body:\n      ' + '\n      '.join(list(difflib.unified_diff(a, b, 'rust', 'java', n=0, lineterm=''))[2:args.show]))
        if effects['rust'] != effects['java']:
            a = json.dumps(effects['rust'], indent=1, sort_keys=True, ensure_ascii=False, default=str).splitlines()
            b = json.dumps(effects['java'], indent=1, sort_keys=True, ensure_ascii=False, default=str).splitlines()
            problems.append('db:\n      ' + '\n      '.join(list(difflib.unified_diff(a, b, 'rust', 'java', n=1, lineterm=''))[2:args.show]))
        tables = ','.join(sorted(effects['java'])) or '-'
        if problems:
            diffs += 1
            print(f'DIFF {case["name"]} [{rs}/{js}] tables={tables}')
            for p in problems:
                print('    ' + p)
        else:
            ok += 1
            print(f'OK   {case["name"]} [{rs}] tables={tables}')
    print(f'{ok} OK, {diffs} DIFF')


if __name__ == '__main__':
    main()
