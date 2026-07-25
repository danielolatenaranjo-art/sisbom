import urllib.request
import json

apiKey = "AIzaSyB6alPrjMaahB2r7-9y75_Zw1sPVSJA5CA"
projectId = "sisbom-de5f8"
email = "224@sisbom.com"
password = "1805_secure_sisbom"

# 1. Authenticate to get ID token
url_auth = f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={apiKey}"
payload_auth = json.dumps({
    "email": email,
    "password": password,
    "returnSecureToken": True
}).encode('utf-8')

req_auth = urllib.request.Request(url_auth, data=payload_auth, headers={'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(req_auth) as response:
        auth_data = json.loads(response.read().decode())
        id_token = auth_data['idToken']
        print("Auth Success!")
except Exception as e:
    print("Auth failed:", e)
    exit(1)

# 2. Patch document
url_patch = f"https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/accesos/central?updateMask.fieldPaths=puerta"
payload_patch = json.dumps({
    "fields": {
        "puerta": {
            "booleanValue": False
        }
    }
}).encode('utf-8')

# Python's urllib.request doesn't support PATCH natively unless we subclass or specify method
req_patch = urllib.request.Request(
    url_patch, 
    data=payload_patch, 
    headers={
        'Authorization': f'Bearer {id_token}',
        'Content-Type': 'application/json'
    },
    method='PATCH'
)

try:
    with urllib.request.urlopen(req_patch) as response:
        print("PATCH Success!")
        print(response.read().decode())
except Exception as e:
    print("PATCH failed:", e)
    if hasattr(e, 'read'):
        print(e.read().decode())
