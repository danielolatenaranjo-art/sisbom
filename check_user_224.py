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
        local_id = auth_data['localId']
        print(f"Auth Success! localId (UID): {local_id}")
except Exception as e:
    print("Auth failed:", e)
    exit(1)

# 2. Get personal/224 document
url_doc = f"https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/personal/224"
req_doc = urllib.request.Request(url_doc, headers={
    'Authorization': f'Bearer {id_token}'
})

try:
    with urllib.request.urlopen(req_doc) as response:
        doc_data = json.loads(response.read().decode())
        print("\n--- User 224 Profile ---")
        fields = doc_data.get('fields', {})
        for k, v in fields.items():
            print(f"{k}: {list(v.values())[0]}")
except Exception as e:
    print("Failed to read personal/224:", e)

# 3. Get accesos/central document
url_central = f"https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/accesos/central"
req_central = urllib.request.Request(url_central, headers={
    'Authorization': f'Bearer {id_token}'
})

try:
    with urllib.request.urlopen(req_central) as response:
        central_data = json.loads(response.read().decode())
        print("\n--- Central Document ---")
        fields = central_data.get('fields', {})
        for k, v in fields.items():
            print(f"{k}: {list(v.values())[0]}")
except Exception as e:
    print("Failed to read accesos/central:", e)
