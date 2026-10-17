"""Compares Rust (4100) and Java (4101) responses for the same requests.

Usage: python parity.py [--cookie COOKIE] [--only SUBSTR] [--show N] [--gzip] [--headers-only]
Prints one line per route (OK / DIFF) and, for diffs, the differing headers and the first lines
of a unified body diff. Bodies are compared after replacing the port.
"""
import argparse, os, base64, difflib, gzip, http.client, json, re, sqlite3, subprocess

# Working files (the apps' data copies, cookie.txt, the last differing pair) live in PARITY_WORK.
ROOT = os.environ.get('PARITY_WORK', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'work'))
L = json.load(open(os.path.join(os.environ['CAMPFIRE_SEED'], 'labels.json')))
IGNORED = {'date', 'x-request-id', 'x-runtime', 'server', 'content-length', 'connection', 'keep-alive', 'transfer-encoding',
           # Thruster's, which Java doesn't emulate; and headers Java sets on every response from configuration,
           # where Rails leaves them off files and error pages.
           'x-cache', 'x-frame-options', 'x-content-type-options', 'x-xss-protection', 'x-permitted-cross-domain-policies',
           'referrer-policy', 'x-version', 'x-rev',
           # Bodies are compared decompressed; Java leaves empty bodies and images uncompressed, Rust's proxy doesn't.
           'content-encoding'}


def fetch(port, method, path, headers, body):
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=30)
    conn.request(method, path, body=body, headers=headers)
    r = conn.getresponse()
    data = r.read()
    hs = [(k.lower(), v) for k, v in r.getheaders()]
    if dict(hs).get('content-encoding') == 'gzip':
        data = gzip.decompress(data)
    conn.close()
    return r.status, hs, data


def norm_headers(hs):
    out = []
    for k, v in hs:
        if k in IGNORED:
            continue
        v = v.replace(':4100', ':PORT').replace(':4101', ':PORT').replace(':4102', ':PORT').replace(':4103', ':PORT').replace(':4104', ':PORT').replace(':4105', ':PORT')
        if k == 'cache-control':
            v = ', '.join(sorted(d.strip() for d in v.split(',')))
            if v == 'no-cache':
                continue  # Java's default for responses without one, error pages included
        if k == 'vary':
            # Java varies on Accept-Encoding only where it compresses (Thruster adds it to everything) and on
            # Accept only where an endpoint offers more than one type.
            v = ', '.join(sorted(t.strip() for t in v.split(',') if t.strip().lower() not in ('accept-encoding', 'accept')))
            if not v:
                continue
        if k == 'set-cookie':
            # Compared by meaning: attribute case and order are the framework's, and `expires` is
            # the time of the response.
            name, _, rest = v.partition('=')
            value, _, attrs = rest.partition(';')
            attrs = sorted(a.strip().lower() if not a.strip().lower().startswith('expires=') else 'expires'
                           for a in attrs.split(';') if a.strip() and not a.strip().lower().startswith('max-age='))
            v = name + '=<' + ('empty' if not value else 'value') + '>; ' + '; '.join(attrs)
        out.append(f'{k}: {v}')
    return sorted(out)


def norm_body(b):
    s = b.decode('utf-8', 'replace')
    s = s.replace(':4100', ':PORT').replace(':4101', ':PORT').replace(':4102', ':PORT').replace(':4103', ':PORT').replace(':4104', ':PORT').replace(':4105', ':PORT')
    # Transfer links are signed with a millisecond expiry, so they differ between any two renders.
    s = re.sub(r'/session/transfers/[A-Za-z0-9=_-]+(--[0-9a-f]+)?', '/session/transfers/ID', s)

    def qr(m):
        try:
            url = base64.urlsafe_b64decode(m.group(1) + '=' * (-len(m.group(1)) % 4)).decode()
        except Exception:
            return m.group(0)
        url = re.sub(r':410[0-2]', ':PORT', url)
        return '/qr_code/' + re.sub(r'/session/transfers/[A-Za-z0-9=_-]+(--[0-9a-f]+)?', '/session/transfers/ID', url)
    return re.sub(r'/qr_code/([A-Za-z0-9=_-]+)', qr, s)


def same_json(a, b):
    try:
        return json.loads(a) == json.loads(b)
    except ValueError:
        return False


def routes():
    db = sqlite3.connect(os.path.join(ROOT, 'java', 'db', 'production.sqlite3'))
    r = lambda k: L['rooms.' + k]
    m = lambda k: L['messages.' + k]
    u = lambda k: L['users.' + k]
    out = [
        '/', '/rooms', f'/rooms/{r("watercooler")}', f'/rooms/{r("hq")}', f'/rooms/{r("designers")}', f'/rooms/{r("pets")}',
        f'/rooms/{r("david_and_jason")}', f'/rooms/{r("david_and_kevin")}', f'/rooms/{r("group_direct")}', f'/rooms/{r("broken")}',
        f'/rooms/{r("archive")}', f'/rooms/{r("quiet")}', '/rooms/opens', '/rooms/999999',
        f'/rooms/{r("watercooler")}/messages', f'/rooms/{r("watercooler")}/messages?before={m("busy_060")}',
        f'/rooms/{r("watercooler")}/messages?after={m("busy_060")}', f'/rooms/{r("watercooler")}/@{m("busy_060")}',
        '/users/me/sidebar', '/searches', '/searches?q=coffee', '/searches?q=pizza', '/searches?q=%22quoted%22+or',
        f'/users/{u("david")}', f'/users/{u("jason")}', f'/users/{u("bender")}', f'/users/{u("mallory")}', '/users/me/profile',
        '/users/me/push_subscriptions', '/autocompletable/users?query=d', '/autocompletable/users?query=j&format=json',
        f'/autocompletable/users?query=&room_id={r("designers")}',
        '/account/edit', '/account/users', '/account/bots', '/account/bots/new', f'/account/bots/{u("bender")}/edit',
        '/account/custom_styles/edit', '/account/logo', '/account/logo?size=small',
        '/rooms/opens/new', '/rooms/closeds/new', '/rooms/directs/new', f'/rooms/opens/{r("watercooler")}/edit',
        f'/rooms/closeds/{r("designers")}/edit', f'/rooms/directs/{r("david_and_jason")}/edit',
        f'/rooms/{r("watercooler")}/involvement', f'/rooms/{r("watercooler")}/refresh?since=0',
        f'/messages/{m("boosted_many")}/boosts', f'/messages/{m("boosted_many")}/boosts/new',
        f'/users/{L["avatar_tokens.jason"]}/avatar', f'/users/{L["avatar_tokens.david"]}/avatar',
        '/webmanifest', '/service-worker', '/up', '/up.json', '/nope', '/rooms/1/settings',
        f'/rooms/{r("watercooler")}/{L["bot_keys.bender"]}/messages',
    ]
    for key, mid in L.items():
        if not key.startswith('messages.') or key.startswith('messages.busy'):
            continue
        row = db.execute('select room_id from messages where id = ?', (mid,)).fetchone()
        if row:
            out.append(f'/rooms/{row[0]}/messages/{mid}')
    return out


def frame_routes():
    """Pages Turbo loads into frames, and the message pages that ignore the frame layout."""
    r = lambda k: L['rooms.' + k]
    m = lambda k: L['messages.' + k]
    hq = r('designers')
    return [
        '/users/me/sidebar', f'/rooms/{r("watercooler")}', '/searches?q=coffee', '/account/edit', '/rooms/directs/new',
        f'/rooms/{r("watercooler")}/involvement', f'/messages/{m("boosted_many")}/boosts/new', f'/messages/{m("boosted_many")}/boosts',
        f'/rooms/{hq}/messages/{m("plain")}/edit', f'/rooms/{hq}/messages/{m("image")}/edit', f'/rooms/{hq}/messages/{m("plain")}',
        f'/rooms/{r("watercooler")}/messages', '/rooms/opens/new', f'/users/{L["users.jason"]}',
    ]


ANON = ['/session/new', f'/join/{L["join_codes.signal"]}', '/first_run', '/', '/up', '/webmanifest']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--cookie', default=open(os.path.join(ROOT, 'cookie.txt')).read().strip())
    ap.add_argument('--only', default='')
    ap.add_argument('--show', type=int, default=12)
    ap.add_argument('--gzip', action='store_true')
    ap.add_argument('--headers-only', action='store_true')
    ap.add_argument('--java-port', type=int, default=4101)
    ap.add_argument('--host', default='', help='Host header sent to both, so bodies (and ETags) carry no port')
    ap.add_argument('--ua', default='safari', help='safari, chrome-win, firefox-linux, android, iphone, edge-mac, curl or a literal UA')
    a = ap.parse_args()
    agents = {
        'safari': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15',
        'chrome-win': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
        'firefox-linux': 'Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0',
        'android': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36',
        'iphone': 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1',
        'edge-mac': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0',
        'curl': 'curl/8.11.1',
    }
    base = {'Accept': 'text/html,application/xhtml+xml', 'User-Agent': agents.get(a.ua, a.ua)}
    if a.host:
        base['Host'] = a.host
    if a.gzip:
        base['Accept-Encoding'] = 'gzip'
    cases = [(p, a.cookie, {}) for p in routes()] + [(p, None, {}) for p in ANON]
    cases += [(p, a.cookie, {'Turbo-Frame': 'x'}) for p in frame_routes()]
    ok = diff = 0
    for path, cookie, extra in cases:
        if a.only and a.only not in path:
            continue
        h = dict(base)
        h.update(extra)
        if cookie:
            h['Cookie'] = cookie
        rs = fetch(4100, 'GET', path, h, None)
        js = fetch(a.java_port, 'GET', path, h, None)
        label = ('anon ' if not cookie else '') + ('frame ' if extra else '') + path
        hr, hj = norm_headers(rs[1]), norm_headers(js[1])
        br, bj = norm_body(rs[2]), norm_body(js[2])
        same_body = a.headers_only or br == bj or same_json(br, bj)
        if same_body and br != bj:
            # Rust escapes <, > and & in JSON as Rails does; Java doesn't. The ETag hashes those bytes.
            hr, hj = [h for h in hr if not h.startswith('etag:')], [h for h in hj if not h.startswith('etag:')]
        if rs[0] == js[0] and hr == hj and same_body:
            ok += 1
            print(f'OK   {rs[0]} {label}')
            continue
        diff += 1
        print(f'DIFF {label}: status rust={rs[0]} java={js[0]}')
        for line in difflib.unified_diff(hr, hj, 'rust', 'java', lineterm='', n=0):
            if not line.startswith(('---', '+++', '@@')):
                print('   H ' + line[:220])
        if not same_body:
            open(ROOT + '/rust.txt', 'w', encoding='utf-8', newline='').write(br)
            open(ROOT + '/java.txt', 'w', encoding='utf-8', newline='').write(bj)
            out = subprocess.run(['diff', '-U1', ROOT + '/rust.txt', ROOT + '/java.txt'], capture_output=True, text=True,
                                 encoding='utf-8', errors='replace').stdout
            lines = out.splitlines()
            for line in lines[2:2 + a.show]:
                print('   B ' + line[:220])
            if len(lines) > 2 + a.show:
                print(f'   B ... {len(lines) - 2 - a.show} more diff lines')
    print(f'\n{ok} identical, {diff} different')


main()
