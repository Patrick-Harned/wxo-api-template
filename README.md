
# WXO Embedded Chat with SSO and OAuth2 Token Exchange

## Overview

This guide shows how to embed watsonx Orchestrate (WXO) chat in your application with SSO authentication, where WXO automatically exchanges your SSO token to call protected APIs on behalf of users.

**The flow:**
```
User → SSO Login → Your App → Embedded Chat with JWT → WXO
                                                        ↓
                                          Token Exchange (OAuth)
                                                        ↓
                                                  Your API Tools
```

## Understanding OAuth2 Token Exchange

**What is OAuth2 Token Exchange (RFC 8693)?**

It's a way to exchange one token (SSO token) for another token (API access token) without the user re-authenticating.

**Example scenario:**
1. User logs into your app with Entra ID → gets SSO token: `eyJ0eXAi...abc123`
2. User asks chat a question requiring data from your API
3. WXO takes that SSO token and exchanges it for an API access token
4. WXO calls your API with the new token

**Why do you need an OAuth echo server?**

Most SSO providers (Entra ID, Okta) don't know about your custom APIs. The echo server acts as a translator:

```
WXO: "Here's the user's SSO token. Give me access to the API."
Echo Server: "Valid SSO token ✓. Here's an API access token."
```

The echo server validates the SSO token with your IDP and returns a token your API recognizes.

## What is the SSO Token?

**The `sso_token` is the OAuth2 access token from your SSO provider.**

**Where it comes from:**

```javascript
// 1. User clicks "Login" → redirected to SSO provider
window.location = 'https://login.microsoftonline.com/.../authorize?...'

// 2. SSO provider redirects back with authorization code
// https://yourapp.com/callback?code=AUTH_CODE_HERE

// 3. Your backend exchanges code for tokens
POST https://login.microsoftonline.com/.../token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&
code=AUTH_CODE_HERE&
client_id=YOUR_CLIENT_ID&
client_secret=YOUR_CLIENT_SECRET

// 4. Response contains the SSO token
{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGc...",  ← THIS is your sso_token
  "token_type": "Bearer",
  "expires_in": 3600
}

// 5. Store this token in session/cookie
// 6. Use it to create the WXO JWT
```

**The SSO token:**
- Proves the user authenticated with your SSO provider
- Contains user identity and permissions
- Is what WXO will exchange for an API access token
- Typically expires in 1 hour

## JWT Structure for WXO

**WXO requires a JWT with specific fields.** Understanding the difference between signing and encryption is critical.

### JWT Signing vs User Payload Encryption

**Two different cryptographic operations happen:**

```
┌─────────────────────────────────────────┐
│         JWT (signed with RS256)         │
├─────────────────────────────────────────┤
│ Header: { "alg": "RS256", "typ": "JWT" }│
├─────────────────────────────────────────┤
│ Payload:                                │
│   {                                     │
│     "sub": "user123",                   │
│     "woUserId": "user123",              │
│     "woTenantId": "abc_xyz",            │
│     "user_payload": "aGV5... ← ENCRYPTED"│
│   }                                     │
├─────────────────────────────────────────┤
│ Signature: YOUR_PRIVATE_KEY signs this │
└─────────────────────────────────────────┘

user_payload contains (after WXO decrypts):
{
  "sso_token": "eyJ0eXAiOiJKV1QiLCJh..."
}
```

**Why two operations?**

1. **JWT Signature (RS256):** Proves the JWT came from you and wasn't tampered with
   - You sign with YOUR private key
   - WXO verifies with YOUR public key
   - Protects: sub, woUserId, woTenantId, encrypted user_payload

2. **User Payload Encryption (RSA-OAEP):** Hides sensitive data (SSO token) from browser
   - You encrypt with IBM's public key
   - WXO decrypts with IBM's private key
   - Protects: sso_token inside user_payload

**The browser can read the JWT payload** (it's just base64), but **cannot read the sso_token** (it's encrypted).

### Complete JWT Structure

```json
{
  "sub": "user@example.com",
  "woUserId": "user@example.com",
  "woTenantId": "ahjffda_c7526c9f-3f74-42d6-b062-ae756e31b956",
  "user_payload": "base64_encrypted_string_here",
  "context": {
    "email": "user@example.com",
    "displayName": "John Doe",
    "wxo_role": "user"
  },
  "exp": 1735689600
}
```

**Field Explanations:**

| Field | Required | Description | Example |
|-------|----------|-------------|---------|
| `sub` | Yes | JWT subject - user identifier | `"user@example.com"` |
| `woUserId` | Yes | WXO user ID - must match `sub` | `"user@example.com"` |
| `woTenantId` | Yes |  for ibm cloud this is the full orchestration id which combines the instance id and the tenant id | `"instanceid_tenant456"` |
| `user_payload` | Yes | Base64-encoded encrypted JSON containing `sso_token` | `"aGV5IG15IGZyaWVuZA=="` |
| `context` | Optional | User metadata visible in agent context | `{"email": "...", "displayName": "..."}` |
| `exp` | Yes | Expiration timestamp (Unix epoch) | `1735689600` |

**Critical: woTenantId Format**

```bash
#  WRONG - Just the tenant UUID
"woTenantId": "c7526c9f-3f74-42d6-b062-ae756e31b956"

# ✓ RIGHT - Full orchestration ID (instance_id + tenant_id)
"woTenantId": "bd8685e8de644893a69fd83941639e7c_c7526c9f-3f74-42d6-b062-ae756e31b956"
```

This ID is used by WXO's credential manager to look up your connection configuration in the database.

### User Payload Structure

**Before encryption, `user_payload` is a JSON object:**

```json
{
  "sso_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsIng1dCI6Ik1yNS1BVWl..."
}
```

**Required fields in user_payload:**
- `sso_token` (string): The OAuth2 access token from your SSO provider

**Optional fields:**
- Any custom data you want WXO to have (will be accessible in agent context)

**After encryption with IBM's public key, it becomes:**
```
"aGV5IG15IGZyaWVuZCBob3cgYXJlIHlvdT9JIGFtIGRvaW5nIHdlbGwgdGhhbmsgeW91IGZvciBhc2tpbmcuLi4="
```

# Cache Management and Multi-Session Handling

## The woUserId Problem

**WXO caches exchanged tokens using `woUserId` as the cache key:**

```javascript
// CM cache lookup
WHERE config_id = 'your-connection' AND user_id = woUserId
```

**Problem with static woUserId:**

```scala
// DON'T DO THIS
val jwtContent = Map[String, Any](
  "woUserId" -> "john@example.com"  // Same for every session
)
```

**What happens:**
1. User logs in → SSO token `abc123` → Cache stores token (expires in 5 min)
2. User logs out, logs back in → SSO token `xyz789` (NEW token)
3. User makes request → WXO finds cached token from step 1 → Uses **old SSO token** ✗

The cache doesn't know the SSO token changed. It will use the old token until it expires or the exchange fails.

## Solution: Hash SSO Token Into woUserId

**Make woUserId unique per SSO token session:**

```scala
val tokenHash = java.security.MessageDigest
  .getInstance("SHA-256")
  .digest(ssoToken.getBytes("UTF-8"))
  .take(8)
  .map("%02x".format(_))
  .mkString

val woUserId = s"${userInfo.sub}_${tokenHash}"
```

**Now:**
1. Session 1: `woUserId = john@example.com_a1b2c3d4` → Cache entry A
2. Session 2: `woUserId = john@example.com_e5f6g7h8` → Cache entry B (different key)

**Result:** Each SSO token gets its own cache entry. When the SSO token changes, WXO automatically uses a new cache.

## Cache Expiration Strategy

**The CM checks cache expiration using the `expires_in` value you return from your echo server.**

**Cache lifecycle:**
```
Token exchanged → Stored with expiry_time = now + expires_in
Later request → Check: expiry_time < now? → If yes: exchange again
```

### The Misalignment Problem

**Three different expiration times:**

| Token | Expires | Controlled By |
|-------|---------|---------------|
| SSO token (from IDP) | 1 hour | Your SSO provider |
| Cached token (in CM) | `expires_in` | Your echo server |
| WXO JWT | `exp` claim | Your JWT creation |

**If cache expires longer than SSO token:**

```
0:00 - SSO token issued (expires 1:00)
0:00 - Echo server returns expires_in: 7200 (2 hours)
0:00 - WXO caches token until 2:00
1:00 - SSO token EXPIRES at IDP
1:30 - User makes request → WXO uses cached token → Exchange with EXPIRED SSO token ✗ Fails
```

### Strategy 1: Conservative Static TTL (Simplest)

**Set a short cache time that's always safe:**

```scala
// Echo server
TokenResponse(
  access_token = ssoToken,
  token_type = "Bearer",
  expires_in = 120  // 2 minutes
)
```

**Why 2 minutes:**
- Short enough that SSO tokens rarely expire within this window
- Long enough to benefit from caching (30x fewer exchanges)
- Simple - no parsing or calculation needed

### Strategy 2: Dynamic TTL (Optimal)

**Calculate how long until the SSO token expires:**

```scala
def getTokenExpiry(ssoToken: String): Int = {
  Try {
    val decoded = jwt.decode(ssoToken, options={"verify_signature": False})
    val exp = decoded.get("exp")  // Unix timestamp
    val now = System.currentTimeMillis() / 1000
    val remaining = (exp - now - 60).toInt  // Subtract 60s buffer
    Math.max(0, Math.min(remaining, 300))  // Cap at 5 minutes
  }.getOrElse(120)  // Fallback to 2 minutes
}

TokenResponse(
  access_token = ssoToken,
  token_type = "Bearer",
  expires_in = getTokenExpiry(ssoToken)
)
```

**Python example:**

```python
import jwt
import time

def get_token_expiry(sso_token):
    try:
        decoded = jwt.decode(sso_token, options={"verify_signature": False})
        exp = decoded.get('exp', 0)
        remaining = exp - int(time.time()) - 60  # 60s safety buffer
        return max(0, min(remaining, 300))  # Cap at 5 minutes
    except:
        return 120  # Fallback

# In echo endpoint
expires_in = get_token_expiry(request.form['assertion'])
```

**Benefits:**
- Cache lasts as long as SSO token is valid
- Automatic adjustment for different token lifetimes
- Never exceeds SSO token expiry

## Complete Example

**JWT Creation with token hash:**

```scala
def createJwtToken(userInfo: UserInfo, ssoToken: String): IO[String] = {
  IO {
    // Hash SSO token for cache key
    val tokenHash = MessageDigest.getInstance("SHA-256")
      .digest(ssoToken.getBytes("UTF-8"))
      .take(8).map("%02x".format(_)).mkString
    
    val jwtContent = Map[String, Any](
      "sub" -> userInfo.sub,
      "woUserId" -> s"${userInfo.sub}_${tokenHash}",  // Unique per SSO token
      "woTenantId" -> config.orchestrationId,
      "user_payload" -> encryptUserPayload(UserPayload(ssoToken)),
      "context" -> Map("email" -> userInfo.email.getOrElse(""))
    )
    
    Jwt.encode(JwtClaim(writeToString(jwtContent), expiresAt), privateKey, RS256)
  }
}
```

**Echo server with dynamic expiry:**

```scala
lazy val echoToken: ServerEndpoint[Any, IO] =
  endpoint.post
    .in("oauth" / "echo")
    .in(formBody[EchoTokenRequest])
    .out(jsonBody[TokenResponse])
    .serverLogic { req =>
      IO.pure(Right(
        TokenResponse(
          access_token = req.assertion,
          token_type = "Bearer",
          expires_in = calculateTokenExpiry(req.assertion)
        )
      ))
    }

def calculateTokenExpiry(ssoToken: String): Int = {
  Try {
    val decoded = Jwt.decode(ssoToken)
    val exp = decode[Map[String, Any]](decoded.content).toOption
      .flatMap(_.get("exp").flatMap {
        case n: BigInt => Some(n.toLong)
        case n: Int => Some(n.toLong)
        case _ => None
      })
    
    exp.map { e =>
      val remaining = (e - System.currentTimeMillis() / 1000 - 60).toInt
      Math.max(0, Math.min(remaining, 300))
    }.getOrElse(120)
  }.getOrElse(120)
}
```

## Quick Reference

| Scenario | woUserId | Echo expires_in |
|----------|----------|-----------------|
| **Recommended** | `${userId}_${hash(ssoToken)}` | Dynamic (parse SSO exp) or 120s |
| Simple/Testing | `${userId}_${hash(ssoToken)}` | 120s (2 min) |
| High Security | `${userId}_${hash(ssoToken)}` | 60s (1 min) |

**Key points:**
- ✓ Always hash SSO token into `woUserId`
- ✓ Return conservative `expires_in` or calculate from SSO token
- ✓ Cache invalidates automatically when SSO token changes

## Step-by-Step Implementation

### Prerequisites


### Step 1: Generate RSA Key Pair

```bash
# Generate 4096-bit RSA private key in PEM format
ssh-keygen -t rsa -b 4096 -m PEM -f wxo-jwt.key

# Extract public key
openssl rsa -in wxo-jwt.key -pubout -outform PEM -out wxo-jwt.key.pub

# Convert to base64 for environment variables
base64 -w 0 wxo-jwt.key > wxo-jwt.key.b64
base64 -w 0 wxo-jwt.key.pub > wxo-jwt.key.pub.b64
```

**Keep the private key secure** - it signs your JWTs. Anyone with this key can impersonate users.

### Step 2: Configure WXO Security

1. Log into WXO → Your Agent → **Settings** → **Security**
2. **Enable Secure Embed Flow**
3. **Upload your public key** (`wxo-jwt.key.pub`)
4. **Copy IBM's public key** for encrypting user payload (looks like):
   ```
   -----BEGIN PUBLIC KEY-----
   MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA...
   -----END PUBLIC KEY-----
   ```
5. Save as `WXO_IBM_PUBLIC_KEY_BASE64` environment variable

### Step 3: Set Up OAuth Echo Server

The echo server validates SSO tokens and returns access tokens.

**What WXO sends to your echo server:**

```http
POST /oauth/echo HTTP/1.1
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&
assertion=eyJ0eXAiOiJKV1QiLCJhbGc...&  ← This is the sso_token
client_id=your-echo-client-id&
client_secret=your-echo-client-secret&
scope=admin
```

**Your echo server must return:**

```json
{
  "access_token": "your_api_access_token_here",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

**Example implementation (Scala Tapir):**

```scala
lazy val echoToken: ServerEndpoint[Any, IO] =
  endpoint.post
    .in("oauth" / "echo")
    .in(formBody[Map[String, String]])
    .out(jsonBody[TokenResponse])
    .errorOut(jsonBody[ApiError])
    .serverLogic { formData =>
      // Everything runs inside IO
      for {
        assertion <- IO.pure(
          formData
            .get("assertion")
            .toRight(
              ApiError(
                s"Missing assertion in request. Requested fields: ${formData.toList.map { case (k, v) => s"$k:$v" }.mkString(",")}"
              )
            )
        )
        _ <- IO.println {
          val clientIdInfo = formData
            .get("client_id")
            .map { value =>
              val redacted =
                if (value.length > 6) s"${value.substring(0, 6)}...[REDACTED]"
                else "[REDACTED]"
              val problematic =
                if (value == "null" || value.isEmpty)
                  s", WARNING: problematic value ('$value')"
                else ""
              s"client_id: YES, value: '$redacted'$problematic"
            }
            .getOrElse("client_id: NO (MISSING KEY)")

          val clientSecretInfo = formData
            .get("client_secret")
            .map { value =>
              val redacted =
                if (value.length > 6) s"${value.substring(0, 6)}...[REDACTED]"
                else "[REDACTED]"
              val problematic =
                if (value == "null" || value.isEmpty)
                  s", WARNING: problematic value ('$value')"
                else ""
              s"client_secret: YES, value: '$redacted'$problematic"
            }
            .getOrElse("client_secret: NO (MISSING KEY)")

          val otherProblematicFields = formData.toList
            .filter { case (key, value) =>
              key != "client_id" && key != "client_secret" && (value == "null" || value.isEmpty)
            }
            .map { case (key, value) => s"$key:'$value'" }
            .mkString(", ")

          val otherProblematicString = if (otherProblematicFields.nonEmpty) {
            s"\n  Other problematic fields (empty or 'null' string): $otherProblematicFields"
          } else ""

          s"Submitted form data analysis:\n  $clientIdInfo\n  $clientSecretInfo$otherProblematicString"
        }
        result <- assertion match {
          case Left(err) => IO.pure(Left(err))

          case Right(assertionValue) =>
            val oidc = OIDCConfig.fromEnv
            val ts   = TokenService(oidc)

            // Continue inside IO
            for {
              /*
              clientId <- IO.pure(
                formData
                  .get("client_id")
                  .toRight(ApiError("missing client_id in form"))
              )

              clientSecret <- IO.pure(
                formData
                  .get("client_secret")
                  .toRight(ApiError("missing client_secret in form"))
              )

              authCheck <- IO.pure(
                for {
                  id  <- clientId
                  sec <- clientSecret
                  _   <- Either.cond(
                    id == oidc.clientId && sec == oidc.clientSecret,
                    (),
                    ApiError("Unauthorized")
                  )
                } yield ()
              )
               */
              finalResult <- {

                ts.introspectToken(assertionValue).attempt.map {
                  case Left(e)  => Left(ApiError(e.getMessage))
                  case Right(x) =>
                    val now           = System.currentTimeMillis() / 1000
                    val remainingTime = x.exp.map(exp => (exp - now).toInt)
                    val maxExpiration =
                      sys.env
                        .get("TOKEN_EXPIRY")
                        .flatMap(_.toIntOption)
                        .getOrElse(300)
                    val cappedExpiration =
                      remainingTime.map(t => Math.min(t, maxExpiration))

                    Right(
                      TokenResponse(
                        access_token = assertionValue,
                        token_type = "Bearer",
                        expires_in = cappedExpiration
                      )
                    )
                }
              }
            } yield finalResult
        }
      } yield result
    }
```

### Step 4: Configure WXO Connection

**Create `connections.yaml`:**

```yaml
app_id: epm-tool-oauth
spec_version: v1
kind: connection
environments:
  draft:
    kind: oauth_auth_token_exchange_flow
    type: member  # Token per user (not shared)
    sso: true
    server_url: https://epm-tool.com
    app_config:
      header:
        content-type: application/x-www-form-urlencoded
  live:
    kind: oauth_auth_token_exchange_flow
    type: member
    sso: true
    server_url: https://epm-tool.com
    app_config:
      header:
        content-type: application/x-www-form-urlencoded
```

**Import and configure:**

```bash
# Activate environment
orchestrate env activate epm

# Import connection
orchestrate connections import -f connections.yaml

# Set credentials for draft
orchestrate connections set-credentials \
  --app-id epm-tool-oauth \
  --env draft \
  --client-id 'your-echo-client-id' \
  --client-secret 'your-echo-client-secret' \
  --auth-url 'https://epm-tool.com/oauth/echo' \
  --token-url 'https://epm-tool.com/oauth/echo' \
  --scope admin \
  --grant-type "urn:ietf:params:oauth:grant-type:jwt-bearer"

# Set credentials for live
orchestrate connections set-credentials \
  --app-id epm-tool-oauth \
  --env live \
  --client-id 'your-echo-client-id' \
  --client-secret 'your-echo-client-secret' \
  --auth-url 'https://epm-tool.com/oauth/echo' \
  --token-url 'https://epm-tool.com/oauth/echo' \
  --scope admin \
  --grant-type "urn:ietf:params:oauth:grant-type:jwt-bearer"
```

### Step 5: Import Tools

```bash
orchestrate tools import \
  -k openapi \
  -f openapi.yaml \
  --app-id epm-tool-oauth
```

The `--app-id` links tools to the OAuth connection. When WXO calls these tools, it will automatically exchange tokens first.

### Step 6: Create JWT in Your Backend

**Language-agnostic pseudocode:**

```python
# High-level flow
def create_wxo_jwt(user_info, sso_token):
    # 1. Create user payload with SSO token
    user_payload = {
        "sso_token": sso_token  # The SSO token from Step 3
    }
    
    # 2. Encrypt user payload with IBM's public key
    encrypted_payload = encrypt_with_rsa_oaep(
        json.dumps(user_payload),
        ibm_public_key,
        hash_algorithm='SHA-256',
        mgf_algorithm='MGF1-SHA256'
    )
    encrypted_base64 = base64_encode(encrypted_payload)
    
    # 3. Create JWT payload
    jwt_payload = {
        "sub": user_info.email,
        "woUserId": user_info.email,
        "woTenantId": ORCHESTRATION_ID,
        "user_payload": encrypted_base64,
        "context": {
            "email": user_info.email,
            "displayName": user_info.name,
            "wxo_role": "user"
        },
        "exp": current_timestamp + 3600  # 1 hour from now
    }
    
    # 4. Sign JWT with your private key
    jwt_token = sign_jwt(
        jwt_payload,
        your_private_key,
        algorithm='RS256'
    )
    
    return jwt_token
```

**Example in Java:**

```java
import javax.crypto.Cipher;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class WxoJwtService {
    
    private PrivateKey yourPrivateKey;
    private PublicKey ibmPublicKey;
    
    public String encryptUserPayload(String ssoToken) throws Exception {
        // Create user payload
        Map<String, String> userPayload = new HashMap<>();
        userPayload.put("sso_token", ssoToken);
        String json = new ObjectMapper().writeValueAsString(userPayload);
        
        // Encrypt with IBM's public key
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaepParams = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        );
        cipher.init(Cipher.ENCRYPT_MODE, ibmPublicKey, oaepParams);
        
        byte[] encrypted = cipher.doFinal(json.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encrypted);
    }
    
    public String createJwt(String userEmail, String userName, String ssoToken) 
            throws Exception {
        
        String encryptedPayload = encryptUserPayload(ssoToken);
        
        Map<String, Object> context = new HashMap<>();
        context.put("email", userEmail);
        context.put("displayName", userName);
        context.put("wxo_role", "user");
        
        long now = System.currentTimeMillis() / 1000;
        
        return Jwts.builder()
            .setSubject(userEmail)
            .claim("woUserId", userEmail)
            .claim("woTenantId", TENANT_ID)
            .claim("user_payload", encryptedPayload)
            .claim("context", context)
            .setExpiration(new Date((now + 3600) * 1000))
            .setIssuedAt(new Date(now * 1000))
            .signWith(yourPrivateKey, SignatureAlgorithm.RS256)
            .compact();
    }
}
```

**Critical encryption parameters:**
- **Algorithm:** RSA/ECB/OAEPWithSHA-256AndMGF1Padding
- **Hash:** SHA-256
- **MGF:** MGF1 with SHA-256
- **Padding:** OAEP with no label

**Wrong encryption will cause:** "An unexpected error occurred during SSO token generation" (500 error)

### Step 7: Implement Embedded Chat

**Complete HTML page:**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Ask EPM</title>
</head>
<body>
  <h1>Welcome to Ask EPM</h1>
  
  <!-- Chat widget container -->
  <div id="root"></div>

  <script>
    // Configuration for WXO chat
    window.wxOConfiguration = {
      clientVersion: "latest",
      
      // Your WXO orchestration ID
      orchestrationID: "bd8685e8de644893a69fd83941639e7c_c7526c9f-3f74-42d6-b062-ae756e31b956",
      
      // WXO host URL
      hostUrl: "https://orchestrate.ibm.com",
      
      // Where to render the chat
      rootElementId: "root",
      
      // Layout: 'small' (widget) or 'full' (full page)
      layout: "small",
      
      // Show launcher button or render directly
      showLauncher: false,
      
      // THE JWT TOKEN - this is what ties everything together
      // This token contains the encrypted sso_token that WXO will exchange
      token: "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyQGV4YW1wbGUuY29tIiwid29Vc2VySWQiOiJ1c2VyQGV4YW1wbGUuY29tIiwid29UZW5hbnRJZCI6ImJkODY4NWU4ZGU2NDQ4OTNhNjlmZDgzOTQxNjM5ZTdjX2M3NTI2YzlmLTNmNzQtNDJkNi1iMDYyLWFlNzU2ZTMxYjk1NiIsInVzZXJfcGF5bG9hZCI6ImFHVjVJRzE1SUdaeWFXVnVaQ0JvYjNjZ1lYSmxJSGx2ZFQ5SklHRnRJR1J2YVc1bklIZGxiR3dnZEdoaGJtc2dlVzkxSUdadmNpQmhjMnRwYm1jdUxpND0iLCJjb250ZXh0Ijp7ImVtYWlsIjoidXNlckBleGFtcGxlLmNvbSIsImRpc3BsYXlOYW1lIjoiSm9obiBEb2UiLCJ3eG9fcm9sZSI6InVzZXIifSwiZXhwIjoxNzM1Njg5NjAwfQ.signature_here",
      
      // Chat options
      chatOptions: {
        // Agent configuration
        agentId: "your-agent-id",
        agentEnvironmentId: "draft",  // or 'live'
        
        // Event handlers
        onLoad: function(instance) {
          console.log("WXO chat loaded:", instance);
          
          // Store instance for later use
          window.wxoChatInstance = instance;
          
          // Listen to events
          instance.on("chatstarted", (data) => {
            console.log("Chat started:", data);
          });
          
          instance.on("pre:send", (event) => {
            console.log("User sending message:", event.message);
          });
          
          instance.on("receive", (event) => {
            console.log("Received response:", event);
          });
        }
      }
    };

    // Load WXO chat script
    setTimeout(function() {
      const script = document.createElement('script');
      script.src = `${window.wxOConfiguration.hostUrl}/wxochat/wxoLoader.js?embed=true`;
      script.addEventListener('load', function() {
        wxoLoader.init();
      });
      document.head.appendChild(script);
    }, 0);
  </script>
</body>
</html>
```

**Dynamic token loading (recommended):**

```html
<script>
  // Fetch JWT from your backend instead of hardcoding
  async function initChat() {
    // Call your backend endpoint that creates the JWT
    const response = await fetch('/api/create-jwt', {
      credentials: 'include'  // Include session cookies
    });
    
    if (!response.ok) {
      console.error('Failed to get JWT');
      return;
    }
    
    const jwtToken = await response.text();
    
    // Configure WXO with the token
    window.wxOConfiguration = {
      clientVersion: "latest",
      orchestrationID: "bd8685e8de644893a69fd83941639e7c_c7526c9f-3f74-42d6-b062-ae756e31b956",
      hostUrl: "https://orchestrate.ibm.com",
      rootElementId: "root",
      layout: "small",
      showLauncher: false,
      token: jwtToken,  // Dynamically fetched token
      chatOptions: {
        agentId: "your-agent-id",
        agentEnvironmentId: "draft",
        onLoad: function(instance) {
          console.log("Chat loaded");
        }
      }
    };
    
    // Load WXO script
    const script = document.createElement('script');
    script.src = `${window.wxOConfiguration.hostUrl}/wxochat/wxoLoader.js?embed=true`;
    script.onload = () => wxoLoader.init();
    document.head.appendChild(script);
  }
  
  // Initialize when page loads
  initChat();
</script>
```



## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. User Authentication                                          │
└─────────────────────────────────────────────────────────────────┘
    User clicks "Login"
         ↓
    Redirect to SSO Provider (Entra ID)
         ↓
    User enters credentials
         ↓
    SSO Provider redirects back with auth code
         ↓
    Your backend exchanges code for access_token
         ↓
    SSO TOKEN: "eyJ0eXAiOiJKV1Qi..." ← STORE THIS
    
┌─────────────────────────────────────────────────────────────────┐
│ 2. Embedded Chat Initialization                                 │
└─────────────────────────────────────────────────────────────────┘
    Frontend loads your app
         ↓
    Calls /api/create-jwt with session cookie
         ↓
    Backend creates JWT:
      1. Retrieves SSO token from session
      2. Encrypts {"sso_token": "eyJ..."} with IBM's public key
      3. Creates JWT with encrypted payload
      4. Signs JWT with your private key
         ↓
    Returns JWT: "eyJhbGciOiJSUzI1NiI..."
         ↓
    Frontend initializes WXO chat with JWT

┌─────────────────────────────────────────────────────────────────┐
│ 3. User Asks Question Requiring Tool                            │
└─────────────────────────────────────────────────────────────────┘
    User: "Show me my employee data"
         ↓
    WXO determines it needs to call GetEmployeeData tool
         ↓
    WXO extracts woTenantId from JWT
         ↓
    WXO looks up connection config in database
         ↓
    WXO decrypts user_payload to get sso_token
    
┌─────────────────────────────────────────────────────────────────┐
│ 4. Token Exchange                                               │
└─────────────────────────────────────────────────────────────────┘
    WXO → Your OAuth Echo Server:
      POST /oauth/echo
      grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer
      assertion=eyJ0eXAiOiJKV1Qi...  ← The SSO token
      client_id=your-echo-client-id
      client_secret=your-echo-client-secret
         ↓
    Echo Server validates SSO token with IDP
         ↓
    Echo Server returns:
      {
        "access_token": "api_token_abc123",
        "token_type": "Bearer",
        "expires_in": 3600
      }
         ↓
    WXO stores token in runtime_credentials table

┌─────────────────────────────────────────────────────────────────┐
│ 5. Tool Execution                                               │
└─────────────────────────────────────────────────────────────────┘
    WXO → Your API Tool:
      GET /api/employee/data
      Authorization: Bearer api_token_abc123
         ↓
    Your API validates token and returns data
         ↓
    WXO formats response
         ↓
    Chat shows answer to user
```

## Troubleshooting

### "Application Not Found!"

**Cause:** `woTenantId` in JWT doesn't match database.

**Fix:**
```bash
# Check what's in the database
orchestrate tools list

# Ensure JWT uses the correct tenant ID
"woTenantId": "c7526c9f-3f74-42d6-b062-ae756e31b956"
#              
#              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ tenant ID
```

### "An unexpected error occurred during SSO token generation"

**Causes:**
1. **Wrong encryption** - Most common
2. **Missing credentials** - Didn't run `set-credentials` commands
3. **Invalid SSO token** - Token expired or malformed

**Fix encryption:**
```python
# Must use these exact parameters
public_key.encrypt(
    payload_bytes,
    padding.OAEP(
        mgf=padding.MGF1(algorithm=hashes.SHA256()),  # MGF1-SHA256
        algorithm=hashes.SHA256(),                     # OAEP-SHA256
        label=None
    )
)
```

**Verify credentials are set:**
```bash
orchestrate connections describe --app-id epm-tool-oauth --env draft

# Should show:
# Runtime Credentials Required: Yes
# Credentials Status: Configured ✓
```

### "Invalid or expired token"

**Cause:** SSO token expired before WXO could exchange it.

**Fix:** Check token expiration times:
```javascript
// When creating JWT, verify SSO token is still valid
const tokenInfo = jwt.decode(ssoToken);
const expiresAt = tokenInfo.exp;
const now = Math.floor(Date.now() / 1000);

if (expiresAt - now < 300) {  // Less than 5 minutes left
  // Refresh SSO token before creating WXO JWT
  ssoToken = await refreshSSOToken(refreshToken);
}
```

### No calls to echo server

**Cause:** Token cached in `runtime_credentials` table.

**How it works:**
- First request: WXO exchanges token, caches for 1 hour
- Subsequent requests: Uses cached token
- After expiration: Exchanges again

**To test token exchange:**
```sql
-- Clear cached tokens
DELETE FROM runtime_credentials WHERE config_id IN (
  SELECT config_id FROM application_connection_configs 
  WHERE app_id = 'epm-tool-oauth'
);
```

## Security Best Practices

1. **Never log SSO tokens or JWTs in production**
   ```javascript
   // ❌ DON'T
   console.log('SSO Token:', ssoToken);
   
   // ✓ DO
   console.log('SSO Token length:', ssoToken.length);
   ```

2. **Use short JWT expiration times**
   ```javascript
   // 1 hour maximum
   "exp": Math.floor(Date.now() / 1000) + 3600
   ```

3. **Validate SSO tokens before creating JWT**
   ```javascript
   // Check token is still valid
   const introspection = await fetch(IDP_INTROSPECT_URL, {
     method: 'POST',
     body: `token=${ssoToken}`
   });
   
   if (!introspection.active) {
     throw new Error('SSO token expired');
   }
   ```

4. **Use HTTPS everywhere**
   - Embedded chat page
   - OAuth echo server
   - Tool APIs

5. **Rotate keys regularly**
   - Generate new RSA key pair every 90 days
   - Update public key in WXO settings
   - Keep old key for 24 hours to handle in-flight JWTs

## Summary Checklist

- [ ] Generated RSA key pair for JWT signing
- [ ] Configured WXO Security with your public key
- [ ] Obtained IBM's public key for encryption
- [ ] Implemented SSO login flow (OAuth2 authorization code)
- [ ] Created OAuth echo server that validates SSO tokens
- [ ] Configured connection with `connections.yaml`
- [ ] Ran `set-credentials` for draft and live environments
- [ ] Imported tools with `--app-id` flag
- [ ] Implemented JWT creation with proper encryption (SHA-256 OAEP)
- [ ] Embedded chat loads with dynamic JWT from backend
- [ ] Tested tool execution with SSO-authenticated user

**Key Takeaways:**

1. **sso_token** = OAuth2 access token from your SSO provider login
2. **Two crypto operations:** JWT signature (RS256) + user_payload encryption (RSA-OAEP SHA-256)
3. **woTenantId** must include the tenantId 
4. **Echo server** validates SSO tokens and returns API access tokens
5. **Credentials** must be set with `orchestrate connections set-credentials`


# Data Warehouse Query Documentation

This document outlines a set of SQL queries designed to extract specific business intelligence from the Procurement and Financial data warehouse. The queries are grouped by the type of information they retrieve, along with explanations of their logic and the data they surface.

**Assumptions:**
*   The `EPM.DIM_TIME_PERIOD_GREGORIAN` table is used as the central date dimension, with `SK_DAY` as the surrogate key and `DATE` as the actual date value.
*   `YourPONumberHere`, `YourInvoiceNumberHere`, `YourSupplierNumberHere`, `YourMonthNumberHere`, `YourYearNumberHere`, and `YourDaysAgoHere` are placeholders for specific input values.

---

## Purchase Order (PO) Related Questions

### Group 1: PO Header Details (Type, Status, Issue Date)

**Questions:**
*   What type of PO is this?
*   What is the issue date for this PO?
*   What is the status of this PO?

**SQL Query:**

```sql
SELECT
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER,
    dpo.SUPPLIER_PURCHASE_ORDER_HANDSFREE_FLAG AS IsHandsFreePO,
    dpo.SUPPLIER_PURCHASE_ORDER_SERVICES_LINKED_ORDER_CODE AS ServicesOrderCode,
    dpo.SUPPLIER_PURCHASE_ORDER_BLANKET_RELEASE_NUMBER AS BlanketReleaseNumber,
    dtg_issue.DATE AS POIssueDate,
    dpo.SUPPLIER_PURCHASE_ORDER_RELEASE_INDICATOR AS POReleaseStatus, -- Header-level status
    LISTAGG(DISTINCT dpos.PURCHASE_ORDER_CLOSURE_STATUS, ', ') AS ItemClosureStatuses -- Aggregated item-level closure statuses
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER AS dpo
LEFT JOIN
    EPM.DIM_TIME_PERIOD_GREGORIAN AS dtg_issue ON dpo.SK_PO_ISSUE_DATE = dtg_issue.SK_DAY
LEFT JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS fspoi -- Join to item fact table
    ON dpo.SK_S2P_SUPPLIER_PURCHASE_ORDER = fspoi.SK_S2P_SUPPLIER_PURCHASE_ORDER_ITEM
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_ORDER_CLOSURE_STATUS AS dpos -- Join from item fact table to closure status dim
    ON fspoi.SK_ORDER_CLOSURE_STATUS = dpos.SK_ORDER_CLOSURE_STATUS
WHERE
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER = 'YourPONumberHere'
GROUP BY
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER,
    dpo.SUPPLIER_PURCHASE_ORDER_HANDSFREE_FLAG,
    dpo.SUPPLIER_PURCHASE_ORDER_SERVICES_LINKED_ORDER_CODE,
    dpo.SUPPLIER_PURCHASE_ORDER_BLANKET_RELEASE_NUMBER,
    dtg_issue.DATE,
    dpo.SUPPLIER_PURCHASE_ORDER_RELEASE_INDICATOR;
```

**Description of Logic and Surfaced Data:**
This query retrieves various header-level details for a specific Purchase Order.
*   It joins `DIM_S2P_SUPPLIER_PURCHASE_ORDER` (for PO header attributes) with `EPM.DIM_TIME_PERIOD_GREGORIAN` to translate the `SK_PO_ISSUE_DATE` into a readable `POIssueDate`.
*   It also joins through `FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` to `DIM_S2P_ORDER_CLOSURE_STATUS` to gather all distinct item-level closure statuses associated with the PO, which are then aggregated into a single string.
*   **Surfaced Data:** PO Number, flags indicating if it's a hands-free PO or a services order, blanket release number, the PO's issue date, its header-level release status, and a comma-separated list of all distinct closure statuses from its line items.

---

### Group 2: PO Item Details (Cost Center, Requester, Requisition Number, Number of Line Items)

**Questions:**
*   What is the cost center tied to this PO?
*   Who has requested this PO?
*   How many line items are there in this PO?
*   What is the requisition number of this PO?

**SQL Query:**

```sql
SELECT
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER,
    COUNT(DISTINCT dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_NUMBER) AS NumberOfLineItems,
    LISTAGG(DISTINCT dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_COST_CENTER_IDENTIFIER, ', ') AS CostCenters,
    LISTAGG(DISTINCT dra.REQUESTER_NAME, ', ') AS Requesters,
    LISTAGG(DISTINCT dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_REQUISITION_ID, ', ') AS RequisitionNumbers
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS dpoi
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_REQUESTER_ATTRIBUTE AS dra ON dpoi.SK_REQUESTER = dra.SK_S2P_REQUESTER_ATTRIBUTE
WHERE
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER = 'YourPONumberHere'
GROUP BY
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER;
```

**Description of Logic and Surfaced Data:**
This query focuses on the details of individual line items within a specific Purchase Order.
*   It queries `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` for line-item specific attributes.
*   It joins with `EPM_PROCUREMENT.DIM_S2P_REQUESTER_ATTRIBUTE` to resolve the requester's name from the `SK_REQUESTER` foreign key.
*   `COUNT(DISTINCT dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_NUMBER)` calculates the total number of unique line items.
*   `LISTAGG(DISTINCT ...)` is used to concatenate all unique cost centers, requesters, and requisition numbers found across all line items of the specified PO.
*   **Surfaced Data:** PO Number, the total count of line items, a comma-separated list of distinct cost centers, requesters, and requisition numbers associated with the PO.

---

### Group 3: PO Summary Report

**Question:**
*   Provide the PO summary report for this PO

**SQL Query:**

```sql
SELECT
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER,
    ds.SUPPLIER_NAME1 AS SupplierName,
    ds.SUPPLIER_DUNNS_ENTITY_NUMBER AS SupplierDUNS,
    dpo.SUPPLIER_PURCHASING_ORGANIZATION AS PurchasingOrganization,
    dpo.SUPPLIER_PURCHASE_ORDER_CONTRACT_NUMBER AS ContractNumber,
    dpo.SUPPLIER_PURCHASE_ORDER_PAYMENT_TERM_CODE AS PaymentTerms,
    dtg_issue.DATE AS POIssueDate,
    dpo.SUPPLIER_PURCHASE_ORDER_RELEASE_INDICATOR AS POReleaseStatus,
    LISTAGG(DISTINCT dpos.PURCHASE_ORDER_CLOSURE_STATUS, ', ') AS ItemClosureStatuses,
    fpo.SUPPLIER_PURCHASE_ORDER_LOCAL_CURRENCY_NET_AMOUNT AS TotalOrderedLocalCurrencyNet,
    fpo.SUPPLIER_PURCHASE_ORDER_REPORTING_CURRENCY_NET_AMOUNT AS TotalOrderedReportingCurrencyNet,
    SUM(fspoi.SUPPLIER_PURCHASE_ORDER_ITEM_QUANTITY) AS TotalOrderedQuantity,
    COUNT(DISTINCT fspoi.SUPPLIER_PURCHASE_ORDER_ITEM_NUMBER) AS NumberOfLineItems,
    LISTAGG(DISTINCT fspoi.SUPPLIER_PURCHASE_ORDER_ITEM_COMMODITY_CODE, ', ') AS CommodityCodes,
    LISTAGG(DISTINCT dra.REQUESTER_NAME, ', ') AS Requesters
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER AS dpo
JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER AS ds
    ON dpo.SK_S2P_PROCUREMENT_SUPPLIER = ds.SK_SUPPLIER_NUMBER
LEFT JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER AS fpo
    ON dpo.SK_S2P_SUPPLIER_PURCHASE_ORDER = fpo.SK_S2P_SUPPLIER_PURCHASE_ORDER
LEFT JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS fspoi
    ON dpo.SK_S2P_SUPPLIER_PURCHASE_ORDER = fspoi.SK_S2P_SUPPLIER_PURCHASE_ORDER_ITEM
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_ORDER_CLOSURE_STATUS AS dpos
    ON fspoi.SK_ORDER_CLOSURE_STATUS = dpos.SK_ORDER_CLOSURE_STATUS
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_REQUESTER_ATTRIBUTE AS dra
    ON fspoi.SK_REQUESTER = dra.SK_S2P_REQUESTER_ATTRIBUTE
LEFT JOIN
    EPM.DIM_TIME_PERIOD_GREGORIAN AS dtg_issue ON dpo.SK_PO_ISSUE_DATE = dtg_issue.SK_DAY
WHERE
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER = 'YourPONumberHere'
GROUP BY
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER,
    ds.SUPPLIER_NAME1,
    ds.SUPPLIER_DUNNS_ENTITY_NUMBER,
    dpo.SUPPLIER_PURCHASING_ORGANIZATION,
    dpo.SUPPLIER_PURCHASE_ORDER_CONTRACT_NUMBER,
    dpo.SUPPLIER_PURCHASE_ORDER_PAYMENT_TERM_CODE,
    dtg_issue.DATE,
    dpo.SUPPLIER_PURCHASE_ORDER_RELEASE_INDICATOR,
    fpo.SUPPLIER_PURCHASE_ORDER_LOCAL_CURRENCY_NET_AMOUNT,
    fpo.SUPPLIER_PURCHASE_ORDER_REPORTING_CURRENCY_NET_AMOUNT;
```

**Description of Logic and Surfaced Data:**
This query provides a comprehensive summary report for a given Purchase Order by integrating data from multiple dimensions and fact tables.
*   It joins `DIM_S2P_SUPPLIER_PURCHASE_ORDER` (PO header), `DIM_S2P_SUPPLIER` (supplier details), `FACT_S2P_SUPPLIER_PURCHASE_ORDER` (PO financial totals), `FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` (PO line item details), `DIM_S2P_ORDER_CLOSURE_STATUS` (item closure status), `DIM_S2P_REQUESTER_ATTRIBUTE` (requester details), and `EPM.DIM_TIME_PERIOD_GREGORIAN` (for issue date).
*   It aggregates line item quantities, counts distinct line items, and lists distinct commodity codes and requesters.
*   **Surfaced Data:** PO Number, Supplier Name, Supplier DUNS, Purchasing Organization, Contract Number, Payment Terms, PO Issue Date, PO Release Status, aggregated item closure statuses, total ordered amounts in local and reporting currencies, total ordered quantity, number of line items, and lists of commodity codes and requesters.

---

### Group 4: PO Payment Status

**Question:**
*   Is this PO fully paid?

**SQL Query:**

```sql
SELECT
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER,
    SUM(dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_LOCAL_CURRENCY_NET_AMOUNT) AS TotalOrderedAmount,
    SUM(dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_LOCAL_CURRENCY_INVOICED_NET_AMOUNT) AS TotalInvoicedAmount,
    CASE
        WHEN SUM(dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_LOCAL_CURRENCY_NET_AMOUNT) = SUM(dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_LOCAL_CURRENCY_INVOICED_NET_AMOUNT) THEN 'Yes'
        ELSE 'No'
    END AS IsFullyPaid
FROM
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS dpoi
WHERE
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER = 'YourPONumberHere'
GROUP BY
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER;
```

**Description of Logic and Surfaced Data:**
This query determines if a specific Purchase Order has been fully paid by comparing the total ordered amount against the total invoiced amount across all its line items.
*   It queries `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` which contains both the ordered and invoiced net amounts for each line item.
*   It sums these amounts for the specified PO and uses a `CASE` statement to return 'Yes' if they match, indicating full payment, or 'No' otherwise.
*   **Surfaced Data:** PO Number, total ordered amount, total invoiced amount, and a 'Yes'/'No' indicator for whether the PO is fully paid.

---

### Group 5: PO to Invoice Linkage

**Question:**
*   What is the invoice number for this PO?

**SQL Query:**

```sql
SELECT
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER,
    LISTAGG(DISTINCT fsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER, ', ') AS AssociatedInvoiceNumbers
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS dpoi
JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING AS fsi
    ON dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER = fsi.SUPPLIER_PURCHASE_ORDER_NUMBER
    AND dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_NUMBER = fsi.SUPPLIER_PURCHASE_ORDER_ITEM_NUMBER
WHERE
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER = 'YourPONumberHere'
GROUP BY
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER;
```

**Description of Logic and Surfaced Data:**
This query identifies all invoice numbers associated with a specific Purchase Order.
*   It joins `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` with `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING` on both the PO number and item number to accurately link PO line items to invoice line items.
*   `LISTAGG(DISTINCT ...)` collects all unique invoice document numbers linked to the PO.
*   **Surfaced Data:** PO Number and a comma-separated list of all distinct invoice numbers associated with it.

---

### Group 6: PO Approval (Requisition-based)

**Question:**
*   Who approved this PO?

**SQL Query:**

```sql
SELECT
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER,
    LISTAGG(DISTINCT dra.REQUESTER_NAME, ', ') AS RequisitionApprovers
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER AS dpo
JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS dpoi
    ON dpo.SK_S2P_SUPPLIER_PURCHASE_ORDER = dpoi.SK_S2P_SUPPLIER_PURCHASE_ORDER
JOIN
    EPM_PROCUREMENT.FACT_S2P_REQUISITION_ITEM AS fri
    ON dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_REQUISITION_ID = fri.S2P_REQUISITION_ID
    AND dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_REQUISITION_ITEM_NUMBER = fri.S2P_REQUISITION_ITEM_NUMBER
JOIN
    EPM_PROCUREMENT.FACT_S2P_REQUISITION_APPROVAL AS fra
    ON fri.SK_S2P_REQUISITION_ITEM = fra.SK_S2P_REQUISITION_ITEM
JOIN
    EPM_PROCUREMENT.DIM_S2P_REQUESTER_ATTRIBUTE AS dra
    ON fra.SK_S2P_REQUISITION_APPROVER_ID = dra.SK_S2P_REQUESTER_ATTRIBUTE
WHERE
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER = 'YourPONumberHere'
    AND fra.S2P_REQUISITION_APPROVAL_STATUS = 'Approved'
GROUP BY
    dpo.SUPPLIER_PURCHASE_ORDER_NUMBER;
```

**Description of Logic and Surfaced Data:**
This query identifies the individuals who approved the requisition(s) that led to a specific Purchase Order.
*   It traces the lineage from the PO header (`DIM_S2P_SUPPLIER_PURCHASE_ORDER`) to its line items (`DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM`), then to the corresponding requisition items (`FACT_S2P_REQUISITION_ITEM`), and finally to the requisition approval records (`FACT_S2P_REQUISITION_APPROVAL`).
*   The `SK_S2P_REQUISITION_APPROVER_ID` from the approval fact is used to join to `DIM_S2P_REQUESTER_ATTRIBUTE` to get the approver's name.
*   A filter `fra.S2P_REQUISITION_APPROVAL_STATUS = 'Approved'` ensures only actual approvals are considered.
*   **Surfaced Data:** PO Number and a comma-separated list of distinct requesters who approved the associated requisitions.

---

### Group 7: Number of POs with Open Invoices

**Question:**
*   Can you display number of POs that have open invoices tied to it?

**SQL Query:**

```sql
SELECT
    COUNT(DISTINCT dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER) AS NUMBER_OF_POS_WITH_OPEN_INVOICES
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS dpoi
JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING AS fsi
    ON dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER = fsi.SUPPLIER_PURCHASE_ORDER_NUMBER
    AND dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_NUMBER = fsi.SUPPLIER_PURCHASE_ORDER_ITEM_NUMBER
JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE AS dsi
    ON fsi.SK_S2P_SUPPLIER_INVOICE = dsi.SK_S2P_SUPPLIER_INVOICE
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE_STATUS AS dsis
    ON dsi.SUPPLIER_INVOICE_STATUS_CODE = dsis.S2P_SUPPLIER_INVOICE_STATUS_TOP_LEVEL_CODE
WHERE
    dsi.SUPPLIER_INVOICE_CANCELED_FLAG = 'N'
    AND dsi.SUPPLIER_INVOICE_REJECTED_FLAG = 'N'
    AND dsi.SUPPLIER_INVOICE_HOLD_FLAG = 'N'
    AND dsi.SUPPLIER_INVOICE_BLOCKED_FLAG = 'N'
    AND dsi.SUPPLIER_INVOICE_CLEARING_DATE IS NULL
    AND dsis.S2P_SUPPLIER_INVOICE_STATUS_TOP_LEVEL_NAME NOT IN ('Paid', 'Cleared', 'Cancelled');
```

**Description of Logic and Surfaced Data:**
This query counts the number of distinct Purchase Orders that are linked to at least one "open" invoice.
*   It starts from `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` to get PO numbers.
*   It joins to `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING` to link PO items to invoice items.
*   It then joins to `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE` (the invoice header dimension) to access critical status flags and the `SUPPLIER_INVOICE_CLEARING_DATE`.
*   A `LEFT JOIN` to `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE_STATUS` allows for filtering by descriptive status names.
*   The `WHERE` clause applies multiple conditions to define an "open" invoice: it must not be cancelled, rejected, on hold, or blocked, and its `SUPPLIER_INVOICE_CLEARING_DATE` must be `NULL` (meaning not yet paid/cleared). It also explicitly excludes 'Paid', 'Cleared', or 'Cancelled' from the top-level status name.
*   **Surfaced Data:** A single count representing the total number of distinct POs that have at least one open invoice.

---

## Invoice Related Questions

### Group 1: Invoice Status and Amount

**Questions:**
*   What is the status of this invoice?
*   How much is this invoice?

**SQL Query:**

```sql
SELECT
    dsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER AS INVOICE_NUMBER,
    dsi.SUPPLIER_INVOICE_STATUS_CODE AS INVOICE_STATUS_CODE,
    dsi.SUPPLIER_INVOICE_STATUS_DESCRIPTION AS INVOICE_STATUS_DESCRIPTION,
    dsis.S2P_SUPPLIER_INVOICE_STATUS_TOP_LEVEL_NAME AS INVOICE_TOP_LEVEL_STATUS,
    dsi.SUPPLIER_INVOICE_CANCELED_FLAG AS IS_CANCELED,
    dsi.SUPPLIER_INVOICE_REJECTED_FLAG AS IS_REJECTED,
    dsi.SUPPLIER_INVOICE_HOLD_FLAG AS IS_ON_HOLD,
    dsi.SUPPLIER_INVOICE_BLOCKED_FLAG AS IS_BLOCKED,
    fsi_fact.SUPPLIER_INVOICE_LOCAL_CURRENCY_NET_AMOUNT AS LOCAL_CURRENCY_NET_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_LOCAL_CURRENCY_GROSS_AMOUNT AS LOCAL_CURRENCY_GROSS_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_DOCUMENT_CURRENCY_NET_AMOUNT AS DOCUMENT_CURRENCY_NET_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_DOCUMENT_CURRENCY_GROSS_AMOUNT AS DOCUMENT_CURRENCY_GROSS_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_REPORTING_CURRENCY_NET_AMOUNT AS REPORTING_CURRENCY_NET_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_REPORTING_CURRENCY_GROSS_AMOUNT AS REPORTING_CURRENCY_GROSS_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_PLAN_CURRENCY_NET_AMOUNT AS PLAN_CURRENCY_NET_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_PLAN_CURRENCY_GROSS_AMOUNT AS PLAN_CURRENCY_GROSS_AMOUNT
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE AS dsi
LEFT JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE AS fsi_fact
    ON dsi.SK_S2P_SUPPLIER_INVOICE = fsi_fact.SK_S2P_SUPPLIER_INVOICE
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE_STATUS AS dsis
    ON dsi.SUPPLIER_INVOICE_STATUS_CODE = dsis.S2P_SUPPLIER_INVOICE_STATUS_TOP_LEVEL_CODE
WHERE
    dsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER = 'YourInvoiceNumberHere';
```

**Description of Logic and Surfaced Data:**
This query retrieves the status and monetary amounts for a specific invoice.
*   It joins `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE` (for invoice header details and status flags) with `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE` (for the actual financial amounts) and `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE_STATUS` (for descriptive status names).
*   **Surfaced Data:** Invoice Number, various status codes and flags (e.g., `INVOICE_STATUS_CODE`, `IS_CANCELED`, `IS_REJECTED`), and net/gross amounts in local, document, reporting, and plan currencies.

---

### Group 2: Invoice Spend Categories and Attached PO

**Questions:**
*   What are the spend categories for this invoice?
*   What is the PO attached to this invoice?

**SQL Query:**

```sql
SELECT
    fsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER AS INVOICE_NUMBER,
    LISTAGG(DISTINCT dcc.COMMODITY_DESCRIPTION, ', ') AS COMMODITY_CATEGORIES,
    LISTAGG(DISTINCT dsuc.UNSPSC_CODE_DESCRIPTION, ', ') AS UNSPSC_CATEGORIES,
    LISTAGG(DISTINCT fsi.SUPPLIER_PURCHASE_ORDER_NUMBER, ', ') AS ATTACHED_PO_NUMBERS
FROM
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING AS fsi
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_COMMODITY_CODE AS dcc
    ON fsi.SK_S2P_COMMODITY_CODE = dcc.SK_S2P_COMMODITY_CODE
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_UNSPSC_CODE AS dsuc
    ON fsi.SUPPLIER_INVOICE_ITEM_UNSPSC_CODE = dsuc.UNSPSC_CODE
WHERE
    fsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER = 'YourInvoiceNumberHere'
GROUP BY
    fsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER;
```

**Description of Logic and Surfaced Data:**
This query identifies the spend categories (both internal commodity codes and external UNSPSC codes) and any associated Purchase Order numbers for a specific invoice.
*   It queries `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING` (which contains invoice item details including links to commodity and UNSPSC codes, and PO numbers).
*   It joins with `EPM_PROCUREMENT.DIM_S2P_COMMODITY_CODE` and `EPM_PROCUREMENT.DIM_S2P_UNSPSC_CODE` to get descriptive names for the spend categories. Note the join to `DIM_S2P_UNSPSC_CODE` is on the natural key `SUPPLIER_INVOICE_ITEM_UNSPSC_CODE`.
*   `LISTAGG(DISTINCT ...)` aggregates the distinct categories and PO numbers.
*   **Surfaced Data:** Invoice Number, a comma-separated list of commodity categories, UNSPSC categories, and attached PO numbers.

---

### Group 3: Requester of a Specific PO

**Question:**
*   Who is requestor of PO CHN-PO-21-100022

**SQL Query:**

```sql
SELECT
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER AS PO_NUMBER,
    LISTAGG(DISTINCT dra.REQUESTER_NAME, ', ') AS REQUESTERS
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS dpoi
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_REQUESTER_ATTRIBUTE AS dra ON dpoi.SK_REQUESTER = dra.SK_S2P_REQUESTER_ATTRIBUTE
WHERE
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER = 'YourPONumberHere'
GROUP BY
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER;
```

**Description of Logic and Surfaced Data:**
This query identifies the requester(s) associated with a specific Purchase Order.
*   It queries `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` for the PO number and the `SK_REQUESTER` foreign key.
*   It joins with `EPM_PROCUREMENT.DIM_S2P_REQUESTER_ATTRIBUTE` to retrieve the `REQUESTER_NAME`.
*   `LISTAGG(DISTINCT ...)` aggregates all unique requester names for the given PO.
*   **Surfaced Data:** PO Number and a comma-separated list of distinct requesters.

---

### Group 4: Invoices and Spend by Supplier for a Specific Month/Period

**Questions:**
*   Get invoices for supplier 729837166 for december month
*   How much is spend by supplier 729837166 in last 15 days

**SQL Query:**

```sql
SELECT
    ds.SUPPLIER_UNIQUE_NUMBER AS SUPPLIER_NUMBER,
    ds.SUPPLIER_NAME1 AS SUPPLIER_NAME,
    dtg.DATE AS INVOICE_DATE,
    dsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER AS INVOICE_NUMBER,
    fsi_fact.SUPPLIER_INVOICE_LOCAL_CURRENCY_NET_AMOUNT AS LOCAL_CURRENCY_NET_AMOUNT,
    fsi_fact.SUPPLIER_INVOICE_REPORTING_CURRENCY_NET_AMOUNT AS REPORTING_CURRENCY_NET_AMOUNT
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE AS dsi
JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE AS fsi_fact
    ON dsi.SK_S2P_SUPPLIER_INVOICE = fsi_fact.SK_S2P_SUPPLIER_INVOICE
JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER AS ds
    ON dsi.SK_ACCOUNTS_PAYABLE_SUPPLIER = ds.SK_SUPPLIER_NUMBER
JOIN
    EPM.DIM_TIME_PERIOD_GREGORIAN AS dtg
    ON dsi.SK_SUPPLIER_INVOICE_DATE = dtg.SK_DAY
WHERE
    ds.SUPPLIER_UNIQUE_NUMBER = 'YourSupplierNumberHere'
    AND dtg.MONTH_NUMBER = 'YourMonthNumberHere' -- For "december month" (e.g., '12')
    AND dtg.YEAR_NUMBER = 'YourYearNumberHere' -- For "december month" (e.g., '2025')
    -- OR dtg.DATE >= CURRENT_DATE - YourDaysAgoHere DAYS -- For "last 15 days" (e.g., 15)
ORDER BY
    INVOICE_DATE;
```

**Description of Logic and Surfaced Data:**
This query retrieves a list of invoices and their amounts for a specific supplier, allowing for filtering by month/year or a rolling number of days.
*   It joins `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE` (invoice header) with `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE` (invoice amounts), `EPM_PROCUREMENT.DIM_S2P_SUPPLIER` (supplier details), and `EPM.DIM_TIME_PERIOD_GREGORIAN` (invoice date).
*   The `WHERE` clause is designed to be dynamically filtered by `SUPPLIER_UNIQUE_NUMBER` and either a specific `MONTH_NUMBER` and `YEAR_NUMBER` (for a fixed month) or a `DATE` range using `CURRENT_DATE - YourDaysAgoHere DAYS` (for a rolling period).
*   **Surfaced Data:** Supplier Number, Supplier Name, Invoice Date, Invoice Number, and net amounts in local and reporting currencies.

---

### Group 5: Spend by Category (Aggregated)

**Question:**
*   How much spend for each category

**SQL Query:**

```sql
SELECT
    dcc.COMMODITY_DESCRIPTION AS COMMODITY_CATEGORY,
    dsuc.UNSPSC_CODE_DESCRIPTION AS UNSPSC_CATEGORY,
    SUM(fsi.SUPPLIER_INVOICE_ITEM_REPORTING_CURRENCY_NET_AMOUNT) AS TOTAL_REPORTING_CURRENCY_NET_SPEND
FROM
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING AS fsi
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_COMMODITY_CODE AS dcc
    ON fsi.SK_S2P_COMMODITY_CODE = dcc.SK_S2P_COMMODITY_CODE
LEFT JOIN
    EPM_PROCUREMENT.DIM_S2P_UNSPSC_CODE AS dsuc
    ON fsi.SUPPLIER_INVOICE_ITEM_UNSPSC_CODE = dsuc.UNSPSC_CODE
GROUP BY
    dcc.COMMODITY_DESCRIPTION,
    dsuc.UNSPSC_CODE_DESCRIPTION
ORDER BY
    TOTAL_REPORTING_CURRENCY_NET_SPEND DESC;
```

**Description of Logic and Surfaced Data:**
This query calculates the total net spend (in reporting currency) for each distinct commodity and UNSPSC category across all invoice items.
*   It queries `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE_ITEM_LANDING` for invoice item amounts and category keys.
*   It joins with `EPM_PROCUREMENT.DIM_S2P_COMMODITY_CODE` and `EPM_PROCUREMENT.DIM_S2P_UNSPSC_CODE` to get descriptive names for the categories.
*   `SUM(...)` aggregates the `SUPPLIER_INVOICE_ITEM_REPORTING_CURRENCY_NET_AMOUNT` for each unique combination of commodity and UNSPSC category.
*   **Surfaced Data:** Commodity Category, UNSPSC Category, and the total net spend in reporting currency for that category combination.

---

### Group 6: Suppliers with Fully Invoiced POs for Last 2 Months

**Question:**
*   Get list of suppliers which has PO which is fully invoiced for last 2 months

**SQL Query:**

```sql
SELECT
    ds.SUPPLIER_UNIQUE_NUMBER AS SUPPLIER_NUMBER,
    ds.SUPPLIER_NAME1 AS SUPPLIER_NAME,
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER AS PO_NUMBER
FROM
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM AS dpoi
JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER AS ds
    ON dpoi.SK_S2P_PROCUREMENT_SUPPLIER = ds.SK_SUPPLIER_NUMBER
JOIN
    EPM.DIM_TIME_PERIOD_GREGORIAN AS dtg_po_create
    ON dpoi.SK_POITEM_CREATE_DATE = dtg_po_create.SK_DAY
WHERE
    dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_LOCAL_CURRENCY_NET_AMOUNT = dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_LOCAL_CURRENCY_INVOICED_NET_AMOUNT
    AND dtg_po_create.DATE >= CURRENT_DATE - 2 MONTHS
GROUP BY
    ds.SUPPLIER_UNIQUE_NUMBER,
    ds.SUPPLIER_NAME1,
    dpoi.SUPPLIER_PURCHASE_ORDER_NUMBER
HAVING
    SUM(dpoi.SUPPLIER_PURCHASE_ORDER_ITEM_LOCAL_CURRENCY_NET_AMOUNT) > 0;
```

**Description of Logic and Surfaced Data:**
This query identifies suppliers and their Purchase Orders that have been fully invoiced within the last two months.
*   It queries `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_PURCHASE_ORDER_ITEM` for PO item details, including ordered and invoiced amounts.
*   It joins with `EPM_PROCUREMENT.DIM_S2P_SUPPLIER` to get supplier information and `EPM.DIM_TIME_PERIOD_GREGORIAN` to filter by the PO item creation date.
*   The `WHERE` clause filters for PO items where the net ordered amount equals the net invoiced amount (indicating full invoicing) and where the PO item was created within the last two months.
*   The `HAVING` clause ensures only POs with actual positive spend are included.
*   **Surfaced Data:** Supplier Number, Supplier Name, and the PO Number for fully invoiced POs from the last two months.

---

### Group 7: Non-PO Invoices for a Specific Year

**Question:**
*   Get non po invoices for year 2025

**SQL Query:**

```sql
SELECT
    dsi.SUPPLIER_INVOICE_DOCUMENT_NUMBER AS INVOICE_NUMBER,
    dsi.SUPPLIER_INVOICE_SUPPLIER_DATE AS INVOICE_DATE,
    ds.SUPPLIER_NAME1 AS SUPPLIER_NAME,
    fsi_fact.SUPPLIER_INVOICE_LOCAL_CURRENCY_NET_AMOUNT AS LOCAL_CURRENCY_NET_AMOUNT
FROM
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE AS dsi
JOIN
    EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE AS fsi_fact
    ON dsi.SK_S2P_SUPPLIER_INVOICE = fsi_fact.SK_S2P_SUPPLIER_INVOICE
JOIN
    EPM_PROCUREMENT.DIM_S2P_SUPPLIER AS ds
    ON dsi.SK_ACCOUNTS_PAYABLE_SUPPLIER = ds.SK_SUPPLIER_NUMBER
JOIN
    EPM.DIM_TIME_PERIOD_GREGORIAN AS dtg_invoice_date
    ON dsi.SK_SUPPLIER_INVOICE_DATE = dtg_invoice_date.SK_DAY
WHERE
    dsi.SUPPLIER_INVOICE_PURCHASE_ORDER_RELATED_FLAG = 'N'
    AND dtg_invoice_date.YEAR_NUMBER = '2025';
```

**Description of Logic and Surfaced Data:**
This query retrieves a list of invoices that are not associated with a Purchase Order for a specific year.
*   It joins `EPM_PROCUREMENT.DIM_S2P_SUPPLIER_INVOICE` (invoice header) with `EPM_PROCUREMENT.FACT_S2P_SUPPLIER_INVOICE` (invoice amounts), `EPM_PROCUREMENT.DIM_S2P_SUPPLIER` (supplier details), and `EPM.DIM_TIME_PERIOD_GREGORIAN` (invoice date).
*   The `WHERE` clause filters for invoices where `SUPPLIER_INVOICE_PURCHASE_ORDER_RELATED_FLAG` is 'N' (indicating a non-PO invoice) and the `YEAR_NUMBER` from the date dimension matches '2025'.
*   **Surfaced Data:** Invoice Number, Invoice Date, Supplier Name, and the net amount in local currency for non-PO invoices in the specified year.

---


