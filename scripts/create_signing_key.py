#!/usr/bin/env python3
"""Create a private release identity once; never replace an existing signing key."""
import pathlib, secrets, subprocess, os
r=pathlib.Path(__file__).resolve().parents[1]
if (r/'signing.properties').exists():
    print('Reusing existing release signing configuration')
    raise SystemExit(0)
if any((r/'signing'/name).exists() for name in ('bronya-release.p12','ember-release.p12')):
    raise SystemExit('Existing keystore found. Restore signing.properties rather than replacing the signing identity.')
(r/'signing').mkdir(exist_ok=True)
p=secrets.token_urlsafe(28)
f=r/'signing.properties'
with f.open('x') as out: out.write(f'storeFile=signing/bronya-release.p12\nstorePassword={p}\nkeyAlias=bronya\nkeyPassword={p}\n')
f.chmod(0o600)
env=os.environ.copy(); env['BRONYA_SIGN_PASSWORD']=p
subprocess.run(['keytool','-genkeypair','-alias','bronya','-keystore',str(r/'signing/bronya-release.p12'),'-storetype','PKCS12','-storepass:env','BRONYA_SIGN_PASSWORD','-keypass:env','BRONYA_SIGN_PASSWORD','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=BronyaTV, OU=Development, O=BronyaTV, C=CN'],env=env,check=True)
(r/'signing/bronya-release.p12').chmod(0o600)
print('Created private release key. Keep signing/ and signing.properties for future APK updates.')
