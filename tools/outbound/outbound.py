"""Drives a fresh Campfire install through its outbound HTTP client: a bot webhook answered with
text, one answered with a file, and a Web Push delivered through Mozilla's real push service and
decrypted here.

Usage: python outbound.py BASE VAPID_PUBLIC_KEY
Needs `pip install websockets http_ece cryptography`, internet access, and the app able to reach this
script's webhook receiver on 127.0.0.1:25161 (run both with `--network host`). Start the app with the same
VAPID key pair and `DISABLE_SSL=true`.
"""
import asyncio, base64, http.cookiejar, http.server, json, os, re, sys, threading, time, urllib.error, urllib.parse, urllib.request, uuid
import http_ece, websockets
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

BASE, VAPID = sys.argv[1], sys.argv[2]
HOOK = 'http://127.0.0.1:25161'
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
received = []


class Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('content-length', 0))).decode()
        received.append((self.path, self.headers.get('content-type'), body))
        if self.path == '/text':
            reply, ctype = ('pong: ' + json.loads(body)['message']['body']['plain']).encode(), 'text/plain'
        else:
            reply, ctype = PNG, 'image/png'
        self.send_response(200)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)

    def log_message(self, *a):
        pass


threading.Thread(target=http.server.HTTPServer(('127.0.0.1', 25161), Hook).serve_forever, daemon=True).start()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())


def call(opener, method, path, data=None, json_body=None):
    headers = {'Sec-Fetch-Site': 'same-origin', 'Accept': 'text/vnd.turbo-stream.html, text/html'}
    body = None
    if data is not None:
        body = urllib.parse.urlencode(data, doseq=True).encode()
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
    if json_body is not None:
        body = json.dumps(json_body).encode()
        headers['Content-Type'] = 'application/json'
    try:
        r = opener.open(urllib.request.Request(BASE + path, data=body, headers=headers, method=method))
        return r.status, r.headers, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode()


def post_message(opener, room, text):
    status, _, body = call(opener, 'POST', f'/rooms/{room}/messages', {'message[body]': text, 'message[client_message_id]': str(uuid.uuid4())})
    assert status == 200, (status, body[:300])


def direct_room(opener, user_id):
    status, headers, _ = call(opener, 'POST', '/rooms/directs', {'user_ids[]': [user_id]})
    assert status == 302, status
    return re.search(r'/rooms/(\d+)', headers['Location']).group(1)


def wait_for(predicate, what, seconds=20):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if predicate():
            return
        time.sleep(0.5)
    raise AssertionError('timed out waiting for ' + what)


admin = client()
status, _, _ = call(admin, 'POST', '/first_run', {'user[name]': 'Ada', 'user[email_address]': 'ada@example.com', 'user[password]': 'correct horse'})
assert status == 302, status

# Webhooks: every message in a bot's direct room goes to it.
for name, path in (('Echo', '/text'), ('Painter', '/file')):
    status, _, body = call(admin, 'POST', '/account/bots', {'user[name]': name, 'user[webhook_url]': HOOK + path})
    assert status == 302, (status, body[:300])
bot_ids = sorted(set(re.findall(r'/account/bots/(\d+)/edit', call(admin, 'GET', '/account/bots')[2])), key=int)
assert len(bot_ids) == 2, bot_ids
echo_id, painter_id = bot_ids

echo_room = direct_room(admin, echo_id)
post_message(admin, echo_room, 'ping from the e2e check')
wait_for(lambda: 'pong: ping from the e2e check' in call(admin, 'GET', f'/rooms/{echo_room}')[2], 'the text reply')
path, ctype, payload = received[0]
assert path == '/text' and ctype.startswith('application/json'), received[0]
print('webhook text reply: OK, payload message path', json.loads(payload)['message']['path'])

painter_room = direct_room(admin, painter_id)
post_message(admin, painter_room, 'draw something')
wait_for(lambda: 'attachment.png' in call(admin, 'GET', f'/rooms/{painter_room}')[2], 'the file reply')
print('webhook file reply: OK')

# Web Push: a second user, never connected over the cable, subscribed at Mozilla's autopush.
join = re.search(r'/join/([A-Za-z0-9_-]+)', call(admin, 'GET', '/account/edit')[2]).group(1)
reader = client()
status, _, _ = call(reader, 'POST', f'/join/{join}', {'user[name]': 'Bea', 'user[email_address]': 'bea@example.com', 'user[password]': 'correct horse'})
assert status == 302, status
reader_id = max(set(re.findall(r'/users/(\d+)', call(admin, 'GET', '/account/users')[2])), key=int)

key = ec.generate_private_key(ec.SECP256R1())
p256dh = base64.urlsafe_b64encode(key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)).rstrip(b'=').decode()
auth = os.urandom(16)


async def push():
    async with websockets.connect('wss://push.services.mozilla.com/', subprotocols=['push-notification']) as ws:
        await ws.send(json.dumps({'messageType': 'hello', 'use_webpush': True, 'uaid': '', 'channelIDs': []}))
        assert json.loads(await ws.recv())['status'] == 200
        # Tied to the app's VAPID key: autopush then rejects pushes not signed with it.
        await ws.send(json.dumps({'messageType': 'register', 'channelID': str(uuid.uuid4()), 'key': VAPID}))
        registered = json.loads(await ws.recv())
        assert registered['status'] == 200, registered
        status, _, body = call(reader, 'POST', '/users/me/push_subscriptions', json_body={'push_subscription': {
            'endpoint': registered['pushEndpoint'], 'p256dh_key': p256dh, 'auth_key': base64.urlsafe_b64encode(auth).rstrip(b'=').decode()}})
        assert status == 200, (status, body[:300])
        post_message(admin, direct_room(admin, reader_id), 'are you there, Bea?')
        while True:
            message = json.loads(await asyncio.wait_for(ws.recv(), 30))
            if message.get('messageType') == 'notification':
                break
        await ws.send(json.dumps({'messageType': 'ack', 'updates': [{'channelID': message['channelID'], 'version': message['version']}]}))
        data = base64.urlsafe_b64decode(message['data'] + '=' * (-len(message['data']) % 4))
        return json.loads(http_ece.decrypt(data, private_key=key, auth_secret=auth, version='aes128gcm'))


notification = asyncio.run(push())
assert 'are you there, Bea?' in json.dumps(notification), notification
print('web push via autopush: OK', json.dumps(notification)[:240])
