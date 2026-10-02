#!/usr/bin/env python3
"""Create a private release identity once; never replace an existing signing key."""
import pathlib, secrets, subprocess, os
r=pathlib.Path(__file__).resolve().parents[1]
if (r/'signing.properties').exists():
    print('Reusing existing release signing configuration')
    raise SystemExit(0)
if (r/'signing/ember-release.p12').exists():
    raise SystemExit('Existing keystore found. Restore signing.properties rather than replacing the signing identity.')
(r/'signing').mkdir(exist_ok=True)
p=secrets.token_urlsafe(28)
f=r/'signing.properties'
with f.open('x') as out: out.write(f'storeFile=signing/ember-release.p12\nstorePassword={p}\nkeyAlias=ember\nkeyPassword={p}\n')
f.chmod(0o600)
env=os.environ.copy(); env['EMBER_SIGN_PASSWORD']=p
subprocess.run(['keytool','-genkeypair','-alias','ember','-keystore',str(r/'signing/ember-release.p12'),'-storetype','PKCS12','-storepass:env','EMBER_SIGN_PASSWORD','-keypass:env','EMBER_SIGN_PASSWORD','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Ember TV, OU=Development, O=Ember, C=CN'],env=env,check=True)
(r/'signing/ember-release.p12').chmod(0o600)
print('Created private release key. Keep signing/ and signing.properties for future APK updates.')
