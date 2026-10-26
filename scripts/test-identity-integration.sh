#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
# Creates uniquely named accounts/workspaces; JDBC checks clean up only their own temporary sessions.
# Default target is the local profile. Hosted targets must be explicitly disposable.
python3 - "$repo_dir/deployment/local-identity/.env" <<'PY'
import html, json, os, re, secrets, sys, time, urllib.error, urllib.parse, urllib.request
local={}
if os.path.exists(sys.argv[1]):
    with open(sys.argv[1]) as source:
        local=dict(line.strip().split('=',1) for line in source if line.strip() and not line.startswith('#'))
auth=os.environ.get('IDENTITY_TEST_AUTH_URL','http://127.0.0.1:58000/auth/v1').rstrip('/')
rest=os.environ.get('IDENTITY_TEST_REST_URL','http://127.0.0.1:58000/rest/v1').rstrip('/')
for url in (auth,rest):
    host=urllib.parse.urlparse(url).hostname
    if host not in ('127.0.0.1','localhost','::1') and os.environ.get('IDENTITY_TEST_TARGET')!='disposable':
        sys.exit('Hosted tests require IDENTITY_TEST_TARGET=disposable and an explicitly designated disposable project.')
remote=any(urllib.parse.urlparse(url).hostname not in ('127.0.0.1','localhost','::1') for url in (auth,rest))
key=os.environ.get('IDENTITY_TEST_PUBLIC_KEY','' if remote else local.get('ANON_KEY',''))
admin=os.environ.get('IDENTITY_TEST_SERVICE_KEY','' if remote else local.get('SERVICE_ROLE_KEY',''))
if not key or not admin: sys.exit('Missing test API keys. Run local identity.sh init or configure explicit disposable target keys.')
def request(base,path,method='GET',body=None,token=None,expected=(200,201,204)):
    headers={'apikey':admin if token==admin else key,'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(base+path,headers=headers,method=method,
        data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req,timeout=15) as result:
            status=result.status; data=result.read()
    except urllib.error.HTTPError as error:
        status=error.code; data=error.read()
    if status not in expected:
        # Error codes are useful; response payloads can contain credentials or mail links.
        try: code=json.loads(data).get('code') or json.loads(data).get('error_code')
        except Exception: code=None
        raise AssertionError(f'{method} {path.split("?")[0]} returned {status}, code={code}')
    return json.loads(data) if data else None
suffix=secrets.token_hex(8)
password=secrets.token_urlsafe(24)
users=[]
for label in ('manager','recipient','outsider'):
    email=f'm2-{label}-{suffix}@example.test'
    user=request(auth,'/admin/users','POST',{'email':email,'password':password,'email_confirm':True,
        'user_metadata':{'name':label,'organization_name':f'M2 {label} {suffix}'}},admin)
    session=request(auth,'/token?grant_type=password','POST',{'email':email,'password':password})
    verified=request(auth,'/user',token=session['access_token'])
    assert verified['id']==user['id'] and verified.get('email_confirmed_at')
    users.append((email,user,session))
# Local captured-mail proof: no browser verifier or provider SDK is required.
mail=os.environ.get('IDENTITY_TEST_MAIL_URL')
if mail is None and urllib.parse.urlparse(auth).hostname in ('127.0.0.1','localhost','::1'):
    mail='http://127.0.0.1:58025'
if mail:
    def mailbox(path):
        with urllib.request.urlopen(mail.rstrip('/')+path,timeout=5) as result: return json.load(result)
    def delivered_hash(email,kind):
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            for message in mailbox('/api/v1/messages').get('messages',[]):
                if any(recipient.get('Address')==email for recipient in message.get('To',[])):
                    body=mailbox('/api/v1/message/'+message['ID'])
                    for link in re.findall(r'href="([^"]+)"',body.get('HTML','')):
                        params=urllib.parse.parse_qs(urllib.parse.urlparse(html.unescape(link)).query)
                        if params.get('type')==[kind] and params.get('token_hash'):
                            return params['token_hash'][0]
            time.sleep(0.2)
        raise AssertionError('Expected local Auth email was not delivered')
    email=f'm2-email-{suffix}@example.test'
    request(auth,'/signup','POST',{'email':email,'password':password,
        'data':{'organization_name':f'M2 email {suffix}'}})
    token_hash=delivered_hash(email,'email')
    confirmed=request(auth,'/verify','POST',{'token_hash':token_hash,'type':'email'})
    assert confirmed['user']['email_confirmed_at'] and confirmed['access_token']
    request(auth,'/verify','POST',{'token_hash':token_hash,'type':'email'},expected=(400,401,403))
    request(auth,'/verify','POST',{'token_hash':secrets.token_hex(28),'type':'recovery'},expected=(400,401,403))
    request(auth,'/recover','POST',{'email':email})
    recovered=request(auth,'/verify','POST',{'token_hash':delivered_hash(email,'recovery'),'type':'recovery'})
    updated_password=secrets.token_urlsafe(24)
    request(auth,'/user','PUT',{'password':updated_password},recovered['access_token'])
    request(auth,'/token?grant_type=password','POST',{'email':email,'password':updated_password})
    print('PASS: real captured confirmation/recovery email, fresh-client token-hash verification, replay rejection and password setup')
else:
    print('SKIP: captured-email flow (no IDENTITY_TEST_MAIL_URL configured for this target)')
manager,recipient,outsider=users
access=manager[2]['access_token']
def rpc(name,body,token=access,expected=(200,)):
    return request(rest,'/rpc/'+name,'POST',body,token,expected)
workspaces=rpc('my_workspaces',{})
assert len(workspaces)==1 and workspaces[0]['role_id']=='owner'
workspace=workspaces[0]['organization_id']
assert len(rpc('my_workspaces',{},outsider[2]['access_token']))==1
rpc('workspace_team',{'p_workspace_id':workspace},outsider[2]['access_token'],(403,))
request(rest,'/organization_memberships','POST',{'organization_id':workspace,
    'user_id':outsider[1]['id'],'role_id':'owner'},access,(403,))
invitation=rpc('manage_workspace_invitation',{'p_workspace_id':workspace,'p_action':'create',
    'p_email':recipient[0],'p_role':'member'})
assert invitation.get('id')
assert rpc('accept_my_organization_invites',{},recipient[2]['access_token'])==1
assert rpc('accept_my_organization_invites',{},recipient[2]['access_token'])==0
memberships=rpc('my_workspaces',{},recipient[2]['access_token'])
assert any(m['organization_id']==workspace and m['role_id']=='member' for m in memberships)
rpc('workspace_team',{'p_workspace_id':workspace},recipient[2]['access_token'],(403,))
labels=rpc('workspace_actor_labels',{'p_workspace_id':workspace,
    'p_actor_ids':[manager[1]['id'],recipient[1]['id'],outsider[1]['id']]},recipient[2]['access_token'])
assert {record['userId']:record['label'] for record in labels}=={
    manager[1]['id']:'manager',recipient[1]['id']:'recipient'}
rpc('workspace_actor_labels',{'p_workspace_id':workspace,'p_actor_ids':[manager[1]['id']]},
    outsider[2]['access_token'],(403,))
team=rpc('workspace_team',{'p_workspace_id':workspace})
assert len(team['members'])==2
new_workspace=rpc('create_organization',{'p_name':f'M2 extra {suffix}'})
assert new_workspace and len(rpc('my_workspaces',{}))==2
reserved=rpc('manage_workspace_invitation',{'p_workspace_id':workspace,'p_action':'create',
    'p_email':f'failed-{suffix}@example.test'})
assert rpc('revoke_workspace_invitation_reservation',{'p_workspace_id':workspace,
    'p_invite_id':reserved['id']})['ok']
refreshed=request(auth,'/token?grant_type=refresh_token','POST',{'refresh_token':manager[2]['refresh_token']})
assert refreshed['access_token'] and refreshed['refresh_token']
request(auth,'/logout?scope=local','POST',{},refreshed['access_token'])
request(auth,'/user',token=refreshed['access_token'],expected=(401,403))
request(auth,'/token?grant_type=refresh_token','POST',{'refresh_token':refreshed['refresh_token']},expected=(400,401,403))
print('PASS: real Auth login/user/refresh/logout, PostgREST workspace/team/actor-label/invitation contracts and cross-workspace rejection')
if remote and not os.environ.get('IDENTITY_TEST_JDBC_URL'):
    print('SKIP: hosted JDBC session contract (no explicit IDENTITY_TEST_JDBC_URL configured)')
PY

# Exercise the actual application adapters and encrypted PostgreSQL SessionStore.
cd "$repo_dir"
clojure -M:dev:identity-integration
