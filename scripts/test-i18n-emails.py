#!/usr/bin/env python3
"""Check bilingual emails on the local identity profile; retain uniquely named test accounts."""
import json
import secrets
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
settings = dict(line.split('=', 1) for line in (ROOT / 'deployment/local-identity/.env').read_text().splitlines()
                if line and not line.startswith('#'))
auth = f"http://127.0.0.1:{settings.get('IDENTITY_API_PORT', '58000')}/auth/v1"
mail = f"http://127.0.0.1:{settings.get('IDENTITY_MAIL_PORT', '58025')}"


def request(base, path, body=None, *, admin=False, token=None):
    headers = {'Content-Type': 'application/json'}
    if base == auth:
        headers['apikey'] = settings['SERVICE_ROLE_KEY'] if admin else settings['ANON_KEY']
        if admin or token:
            headers['Authorization'] = 'Bearer ' + (settings['SERVICE_ROLE_KEY'] if admin else token)
    req = urllib.request.Request(base + path, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raise AssertionError(f'{path} returned {error.code}') from None


def captured(email):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        for message in request(mail, '/api/v1/messages').get('messages', []):
            if any(recipient.get('Address') == email for recipient in message.get('To', [])):
                return request(mail, '/api/v1/message/' + message['ID'])
        time.sleep(.2)
    raise AssertionError('Expected email was not captured')


def main():
    suffix = secrets.token_hex(6)
    count = 0
    for locale in ('en', 'ru'):
        for kind in ('confirmation', 'invite', 'recovery', 'magic-link'):
            email = f'i18n-{locale}-{kind}-{suffix}@example.test'
            password = secrets.token_urlsafe(24)
            metadata = {'locale': locale, 'display_name': 'Preserved name'}
            if kind == 'confirmation':
                request(auth, '/signup', {'email': email, 'password': password, 'data': metadata})
            elif kind == 'invite':
                request(auth, '/invite', {'email': email, 'data': metadata}, admin=True)
            else:
                request(auth, '/admin/users', {'email': email, 'password': password, 'email_confirm': True,
                                               'user_metadata': metadata}, admin=True)
                path = '/recover' if kind == 'recovery' else '/otp'
                request(auth, path, {'email': email, 'create_user': False})
            message = captured(email)
            body = message['HTML']
            assert f'<html lang="{locale}">' in body, f'{kind}/{locale}: incorrect language'
            expected = 'Продолжить в Tover' if locale == 'ru' else 'Continue to Tover'
            assert expected in body, f'{kind}/{locale}: missing translated action'
            assert ('Подтвердите этот запрос' in body) == (locale == 'ru'), f'{kind}/{locale}: wrong branch'
            assert '/auth/confirm?token_hash=' in body, f'{kind}/{locale}: missing confirmation URL'
            assert 'Tover:' in message['Subject'], f'{kind}/{locale}: wrong subject'
            assert ' / ' in message['Subject'], f'{kind}/{locale}: missing bilingual subject'
            count += 1
    email = f'i18n-fallback-{suffix}@example.test'
    request(auth, '/signup', {'email': email, 'password': secrets.token_urlsafe(24)})
    assert '<html lang="en">' in captured(email)['HTML']
    print(f'{count} bilingual email flows and English fallback passed. Test accounts retained.')


if __name__ == '__main__':
    main()
