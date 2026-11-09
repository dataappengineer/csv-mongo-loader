# 🔒 HTTPS/TLS Transition - Guida Operativa

**Stato**: ZERO-IMPACT sul codice sorgente + Dockerfile  
**Data**: 2026-11-09  
**Obiettivo**: Transizione da HTTP a HTTPS per server, callback client, e MongoDB TLS

---

## 📋 Indice Rapido

1. [Cosa Settare nel Pod K8s (ZERO MODIFICHE CODICE)](#parte-1-cosa-settare-nel-pod-k8s)
2. [Come Preparare Certificati e Secret](#parte-2-preparazione-certificati-e-secret-k8s)
3. [Test & Verifica](#parte-3-test--verifica)
4. [Se Occorresse Toccare il Codice (Sezione Dedicata)](#parte-4-modifiche-al-codice-solo-se-necessario)

---

---

# PARTE 1: Cosa Settare nel Pod K8s

> **IMPORTANTE**: Questa sezione descrive TUTTO ciò che serve fare a livello infrastrutturale senza toccare alcun file di codice.

## 1.1 Server HTTPS (Spring Boot - Port 8443)

### Variabili d'Ambiente da Aggiungere

```yaml
env:
  # Abilita SSL/TLS in Tomcat embedded
  - name: SERVER_SSL_ENABLED
    value: "true"
  
  # Keystore contenente il certificato server
  - name: SERVER_SSL_KEY_STORE
    value: "/etc/tls/server.p12"
  
  # Tipo keystore (PKCS12 è più moderno di JKS)
  - name: SERVER_SSL_KEY_STORE_TYPE
    value: "PKCS12"
  
  # Alias del certificato dentro il keystore
  - name: SERVER_SSL_KEY_ALIAS
    value: "server"
  
  # Password keystore (meglio da Secret)
  - name: SERVER_SSL_KEY_STORE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: csv-loader-tls-password
        key: password
  
  # Cambio port da 8080 a 8443
  - name: SERVER_PORT
    value: "8443"
```

### Montaggio del Secret nel Pod

```yaml
volumeMounts:
  - name: server-tls
    mountPath: /etc/tls
    readOnly: true

volumes:
  - name: server-tls
    secret:
      secretName: csv-loader-tls
      defaultMode: 0400
```

---

## 1.2 Client HTTPS per Callback (HttpClient Java)

**Situazione**: LoadController.sendCallback() usa HttpClient Java per POST verso callbackUrl.

**Buona notizia**: HttpClient negozia HTTPS automaticamente se l'URL inizia con `https://`.

**Se la callbackUrl usa certificato autofirmato o CA aziendale:**

```yaml
env:
  # Instruire Java di usare CA custom (opzionale, solo se necessario)
  - name: JAVA_TOOL_OPTIONS
    value: >
      -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
      -Djavax.net.ssl.trustStorePassword=changeit
      -Djavax.net.ssl.trustStoreType=JKS

volumeMounts:
  - name: ca-bundle
    mountPath: /etc/ssl/certs
    readOnly: true

volumes:
  - name: ca-bundle
    secret:
      secretName: ca-bundle
```

**Nota**: Se la callbackUrl usa certificato Let's Encrypt o CA pubblico noto, **nessun montaggio aggiuntivo** è richiesto.

---

## 1.3 MongoDB TLS (Driver Client)

**Situazione**: MongoCSVLoader.load() usa `MongoClients.create(mongoUri)`.

**Come abilitare TLS**: Aggiungere parametri alla `mongoUri` nel JSON di richiesta.

### Opzione A: MongoDB con Certificato Pubblico (Let's Encrypt, DigiCert)

```json
{
  "mongoUri": "mongodb://user:password@mongo-prod.example.com:27017/?tls=true",
  "database": "mydb",
  ...
}
```

**Variabili d'Ambiente Pod**: NESSUNA aggiuntiva (Java Truststore di default contiene CA pubblici).

---

### Opzione B: MongoDB con Certificato Interno/Autofirmato

```json
{
  "mongoUri": "mongodb://user:password@mongo-prod.example.com:27017/?tls=true&tlsCAFile=/etc/ssl/mongo/ca.pem",
  "database": "mydb",
  ...
}
```

**Montaggio Secret nel Pod**:

```yaml
volumeMounts:
  - name: mongo-tls
    mountPath: /etc/ssl/mongo
    readOnly: true

volumes:
  - name: mongo-tls
    secret:
      secretName: mongo-ca-cert
      items:
        - key: ca.pem
          path: ca.pem
```

---

### Opzione C: MongoDB con mTLS (Client Certificate)

```json
{
  "mongoUri": "mongodb://mongo-prod.example.com:27017/?tls=true&tlsCAFile=/etc/ssl/mongo/ca.pem&tlsCertificateKeyFile=/etc/ssl/mongo/client.pem",
  "database": "mydb",
  ...
}
```

**Montaggio Secret nel Pod**:

```yaml
volumeMounts:
  - name: mongo-tls
    mountPath: /etc/ssl/mongo
    readOnly: true

volumes:
  - name: mongo-tls
    secret:
      secretName: mongo-client-tls
      items:
        - key: ca.pem
          path: ca.pem
        - key: client-cert.pem
          path: client.pem
        - key: client-key.pem
          path: client.pem  # Concaten ca + cert + key in un unico file
```

---

---

# PARTE 2: Preparazione Certificati e Secret K8s

## 2.1 Generare Certificato Server (TLS per HTTPS)

### Opzione 1: Autofirmato (per Dev/Test)

```bash
# Genera chiave privata
openssl genrsa -out server-key.pem 2048

# Genera CSR
openssl req -new -key server-key.pem -out server.csr \
  -subj "/CN=csv-mongo-loader/O=MyOrg/C=IT"

# Firma autofirmato (365 giorni)
openssl x509 -req -days 365 -in server.csr \
  -signkey server-key.pem -out server-cert.pem

# Converti in PKCS12 (formato accettato da Tomcat/Spring Boot)
openssl pkcs12 -export \
  -in server-cert.pem -inkey server-key.pem \
  -out server.p12 -name server \
  -passout pass:MyKeyStorePassword123
```

### Opzione 2: Certificato da CA (Prod)

Coordinare con ITO per ottenere:
- `server-cert.pem` (certificato firmato da CA)
- `server-key.pem` (chiave privata)

Poi convertire in PKCS12:

```bash
openssl pkcs12 -export \
  -in server-cert.pem -inkey server-key.pem \
  -out server.p12 -name server \
  -passout pass:MyKeyStorePassword123
```

---

## 2.2 Creare Secret K8s per Certificato Server

```bash
# Crea il Secret dal file PKCS12
kubectl create secret generic csv-loader-tls \
  --from-file=server.p12=./server.p12 \
  -n csv-loader-namespace

# Crea Secret separato per password keystore
kubectl create secret generic csv-loader-tls-password \
  --from-literal=password=MyKeyStorePassword123 \
  -n csv-loader-namespace
```

---

## 2.3 Creare Secret K8s per CA Certificate (Callback/MongoDB)

### Se usi CA aziendale per le chiamate HTTPS in uscita:

```bash
# CA certificate (fornito da ITO)
kubectl create secret generic ca-bundle \
  --from-file=ca.pem=./ca-certificate.pem \
  -n csv-loader-namespace
```

### Se usi CA aziendale per MongoDB TLS:

```bash
# CA certificate MongoDB
kubectl create secret generic mongo-ca-cert \
  --from-file=ca.pem=./mongo-ca.pem \
  -n csv-loader-namespace

# O con mTLS (client cert + key)
kubectl create secret generic mongo-client-tls \
  --from-file=ca.pem=./mongo-ca.pem \
  --from-file=client-cert.pem=./client-cert.pem \
  --from-file=client-key.pem=./client-key.pem \
  -n csv-loader-namespace
```

---

## 2.4 Manifest K8s Completo (Esempio)

```yaml
---
# Secret per certificato server TLS
apiVersion: v1
kind: Secret
metadata:
  name: csv-loader-tls
  namespace: csv-loader-namespace
type: Opaque
data:
  # Inserisci il contenuto di server.p12 in base64
  server.p12: <BASE64_ENCODED_PKCS12_FILE>

---
# Secret per password keystore
apiVersion: v1
kind: Secret
metadata:
  name: csv-loader-tls-password
  namespace: csv-loader-namespace
type: Opaque
stringData:
  password: "MyKeyStorePassword123"

---
# Secret per CA certificate (opzionale, se CA interno)
apiVersion: v1
kind: Secret
metadata:
  name: ca-bundle
  namespace: csv-loader-namespace
type: Opaque
stringData:
  ca.pem: |
    -----BEGIN CERTIFICATE-----
    MIIFjTCCA...
    ...
    -----END CERTIFICATE-----

---
# Secret per MongoDB TLS (opzionale)
apiVersion: v1
kind: Secret
metadata:
  name: mongo-ca-cert
  namespace: csv-loader-namespace
type: Opaque
stringData:
  ca.pem: |
    -----BEGIN CERTIFICATE-----
    MIIFjTCCA...
    ...
    -----END CERTIFICATE-----

---
# Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: csv-mongo-loader
  namespace: csv-loader-namespace
spec:
  replicas: 2
  selector:
    matchLabels:
      app: csv-mongo-loader
  template:
    metadata:
      labels:
        app: csv-mongo-loader
    spec:
      containers:
      - name: app
        image: csv-mongo-loader:latest
        imagePullPolicy: IfNotPresent
        
        ports:
        - name: https
          containerPort: 8443
          protocol: TCP
        
        # Variabili d'Ambiente: Server HTTPS
        env:
        - name: SERVER_SSL_ENABLED
          value: "true"
        - name: SERVER_SSL_KEY_STORE
          value: "/etc/tls/server.p12"
        - name: SERVER_SSL_KEY_STORE_TYPE
          value: "PKCS12"
        - name: SERVER_SSL_KEY_ALIAS
          value: "server"
        - name: SERVER_SSL_KEY_STORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: csv-loader-tls-password
              key: password
        - name: SERVER_PORT
          value: "8443"
        
        # Variabili d'Ambiente: Java (se CA interno per callback)
        - name: JAVA_TOOL_OPTIONS
          value: >
            -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
            -Djavax.net.ssl.trustStorePassword=changeit
            -Djavax.net.ssl.trustStoreType=JKS
        
        # Volumi montati
        volumeMounts:
        - name: server-tls
          mountPath: /etc/tls
          readOnly: true
        - name: ca-bundle
          mountPath: /etc/ssl/certs
          readOnly: true
        - name: mongo-tls
          mountPath: /etc/ssl/mongo
          readOnly: true
        
        # Health checks
        livenessProbe:
          httpGet:
            scheme: HTTPS
            path: /actuator/health
            port: 8443
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        
        readinessProbe:
          httpGet:
            scheme: HTTPS
            path: /actuator/health/readiness
            port: 8443
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 2
        
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
      
      volumes:
      - name: server-tls
        secret:
          secretName: csv-loader-tls
          defaultMode: 0400
      - name: ca-bundle
        secret:
          secretName: ca-bundle
          optional: true
          defaultMode: 0444
      - name: mongo-tls
        secret:
          secretName: mongo-ca-cert
          optional: true
          defaultMode: 0444

---
# Service
apiVersion: v1
kind: Service
metadata:
  name: csv-mongo-loader-svc
  namespace: csv-loader-namespace
spec:
  type: ClusterIP
  ports:
  - name: https
    port: 8443
    targetPort: 8443
    protocol: TCP
  selector:
    app: csv-mongo-loader

---
# Ingress (opzionale, se esposto all'esterno)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: csv-mongo-loader-ingress
  namespace: csv-loader-namespace
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - csv-loader.example.com
    secretName: csv-loader-ingress-tls
  rules:
  - host: csv-loader.example.com
    http:
      paths:
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: csv-mongo-loader-svc
            port:
              number: 8443
```

---

---

# PARTE 3: Test & Verifica

## 3.1 Verificare che il Pod sia UP e HTTPS funzioni

```bash
# Port-forward verso il Pod
kubectl port-forward -n csv-loader-namespace svc/csv-mongo-loader-svc 8443:8443 &

# Test 1: Swagger UI (accetta certificato autofirmato)
curl -k https://localhost:8443/swagger-ui.html

# Test 2: Endpoint API (POST /api/load)
curl -k -X POST https://localhost:8443/api/load \
  -H "Content-Type: application/json" \
  -d '{
    "mongoUri": "mongodb://localhost:27017/?tls=false",
    "database": "test_db",
    "collezione": "test_coll",
    "csvPath": "/data/test.csv",
    "separatore": ",",
    "enclosure": "\"",
    "modo": "TI",
    "logCollezione": "logs"
  }'

# Test 3: Verificare certificato
openssl s_client -connect localhost:8443 < /dev/null | grep -A2 "Subject:"
```

---

## 3.2 Verificare Callback HTTPS

Se usi callback asincrono con URL HTTPS:

```bash
# Esempio: POST con callbackUrl
curl -k -X POST https://localhost:8443/api/load \
  -H "Content-Type: application/json" \
  -d '{
    "mongoUri": "mongodb://localhost:27017",
    "database": "test_db",
    "collezione": "test_coll",
    "csvPath": "/data/test.csv",
    "separatore": ",",
    "enclosure": "\"",
    "modo": "TI",
    "logCollezione": "logs",
    "callbackUrl": "https://callback-server.example.com/receive",
    "callbackUser": "user",
    "callbackPassword": "secret"
  }'

# Output atteso: HTTP 202 (ACCEPTED)
```

---

## 3.3 Verificare MongoDB TLS

Se MongoDB è configurato con TLS:

```bash
# Entrare nel container
kubectl exec -it -n csv-loader-namespace \
  $(kubectl get pods -n csv-loader-namespace -l app=csv-mongo-loader -o jsonpath='{.items[0].metadata.name}') \
  -- /bin/bash

# Inside container: test connessione MongoDB con TLS
mongo "mongodb://user:password@mongo-host:27017/?tls=true" --eval "db.adminCommand('ping')"

# O se ha mTLS:
mongo "mongodb://mongo-host:27017/?tls=true&tlsCAFile=/etc/ssl/mongo/ca.pem&tlsCertificateKeyFile=/etc/ssl/mongo/client.pem" \
  --eval "db.adminCommand('ping')"
```

---

## 3.4 Check Log per Errori SSL

```bash
# Leggi log del Pod
kubectl logs -n csv-loader-namespace -l app=csv-mongo-loader --tail=100

# Se vedi errori di certificato, abilita debug (solo in test):
# Aggiungi temporaneamente a JAVA_TOOL_OPTIONS:
# -Djavax.net.debug=ssl:handshake

# Riavvia Pod per applicare
kubectl rollout restart deployment/csv-mongo-loader -n csv-loader-namespace
```

---

---

# PARTE 4: Modifiche al Codice (Solo se Necessario)

> **IMPORTANTE**: Questa sezione è separata perché in 99% dei casi NON occorre toccare il codice.

## Quando toccare il codice?

✅ **NON OCCORRE toccare il codice se**:
- Usi `mongoUri` come parametro di input JSON (✅ STATO ATTUALE)
- Usi `callbackUrl` come parametro di input JSON (✅ STATO ATTUALE)
- Usi variabili d'ambiente per Server HTTPS (✅ SUPPORTATO NATIVAMENTE DA SPRING BOOT)

❌ **OCCORREREBBE toccare il codice solo se**:
- La `mongoUri` fosse hardcoded nel codice (es. `String uri = "mongodb://localhost:27017"`) → **NON è così**
- La `callbackUrl` fosse hardcoded (es. `String url = "http://...";`) → **NON è così**
- Usassi HTTP client che non supporta HTTPS di default → **HttpClient Java 11+ supporta nativamente**
- Avessi bisogno di certificati client custom per callback → **Gestibile via JAVA_TOOL_OPTIONS**

---

## 4.1 Stato Attuale del Codice (Analisi)

### LoadController.sendCallback() [Line ~113]

```java
// ✅ NESSUNA MODIFICA NECESSARIA
HttpRequest req = HttpRequest.newBuilder()
    .uri(URI.create(url))  // 'url' è passato come parametro, non hardcoded
    .header("Content-Type", "application/json")
    .header("Authorization", "Basic " + credentials)
    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    .timeout(Duration.ofSeconds(30))
    .build();

HttpClient client = HttpClient.newHttpClient();  // ✅ Supporta HTTPS nativamente
HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
```

**Perché nessuna modifica**:
- `url` proviene dal parametro `request.getCallbackUrl()` (variabile)
- HttpClient negozia HTTPS automaticamente
- Certificati vengono dal Truststore JVM

---

### MongoCSVLoader.load() [Line ~41]

```java
// ✅ NESSUNA MODIFICA NECESSARIA
try (MongoClient mongoClient = MongoClients.create(req.getMongoUri())) {
    // 'mongoUri' è passato come parametro, non hardcoded
    // Es: "mongodb://user:pwd@host:27017/?tls=true&tlsCAFile=/etc/ssl/mongo/ca.pem"
```

**Perché nessuna modifica**:
- `mongoUri` proviene dal parametro `request.getMongoUri()` (variabile)
- MongoDB Driver legge parametri URI (tls, tlsCAFile, ecc.)
- Zero modifiche al driver

---

## 4.2 Se Occorresse Aggiungere Logica (Casi Avanzati)

### Caso 1: Validare che MongoDB sia raggiungibile all'avvio

**File**: `CsvMongoLoaderApplication.java`

```java
@Component
public class MongoHealthCheck {
    @Autowired
    private MongoTemplate mongoTemplate;

    @PostConstruct
    public void checkMongoDB() {
        try {
            mongoTemplate.executeCommand("{ ping: 1 }");
            System.out.println("✅ MongoDB connesso con successo");
        } catch (Exception e) {
            System.err.println("❌ MongoDB non raggiungibile: " + e.getMessage());
            throw new IllegalStateException("Cannot reach MongoDB", e);
        }
    }
}
```

**Quando**: Solo se vuoi fallback più veloce in startup.  
**Impatto**: Minimo, solo in fase di init.

---

### Caso 2: Aggiungere Retry/Timeout per Callback

**File**: `LoadController.java` (metodo `sendCallback()`)

```java
void sendCallback(String url, String user, String password, String jobId, LoadResponse result) {
    try {
        result.setJobId(jobId);
        String body = MAPPER.writeValueAsString(result);
        String credentials = Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();

        // ✅ Retry logic (opzionale)
        int maxRetries = 3;
        int retryDelayMs = 1000;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    LOG.info("Callback [jobId=" + jobId + "] → HTTP " + response.statusCode());
                    return;
                }
                
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelayMs);
                    retryDelayMs *= 2;  // Exponential backoff
                }
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw e;
                }
                Thread.sleep(retryDelayMs);
                retryDelayMs *= 2;
            }
        }
    } catch (Exception e) {
        LOG.severe("Callback [jobId=" + jobId + "] FAILED: " + e.getMessage());
    }
}
```

**Quando**: Se i callback sono critici e la rete è inaffidabile.  
**Impatto**: Moderato (aggiunge logica, nessun cambio a interfaccia).

---

### Caso 3: Logging di Debug per TLS

**File**: `CsvMongoLoaderApplication.java`

```java
@SpringBootApplication
public class CsvMongoLoaderApplication {
    
    @PostConstruct
    public void enableDebugLogging() {
        // Abilita debug SSL solo se env var impostata
        String debugSsl = System.getenv("DEBUG_SSL");
        if ("true".equalsIgnoreCase(debugSsl)) {
            System.setProperty("javax.net.debug", "ssl:handshake");
            System.out.println("⚠️ DEBUG SSL ABILITATO - Non usare in PROD");
        }
    }
    
    public static void main(String[] args) {
        SpringApplication.run(CsvMongoLoaderApplication.class, args);
    }
}
```

**Quando**: Solo per troubleshooting iniziale.  
**Impatto**: Zero (condizionato a env var, nessun output in prod).

---

## 4.3 Checklist: Non Modificare Questi File

| File | Motivo |
|------|--------|
| `LoadController.java` (callback) | URL da parametro JSON, HttpClient supporta HTTPS nativamente |
| `MongoCSVLoader.java` | mongoUri da parametro JSON, driver supporta TLS in URI |
| `LoadRequest.java` | Struttura dati, nessun cambio necessario |
| `CsvMongoLoaderApplication.java` | Main application, nessun cambio per HTTPS |
| `application.properties` | Server HTTPS via env, non da properties |
| `Dockerfile` | Certificati via Secret montati, immagine invariata |
| `pom.xml` | Dipendenze già presenti (Spring Boot, MongoDB, HttpClient) |

---

---

# RIASSUNTO OPERATIVO VELOCE

| Aspetto | Azione | Chi | Risultato |
|--------|--------|-----|-----------|
| **Certificato Server** | Generare/procurare PEM; convertire in PKCS12 | DevOps/ITO | file `server.p12` |
| **Secret K8s (Server TLS)** | `kubectl create secret generic csv-loader-tls --from-file=server.p12=./server.p12` | DevOps | Secret creato |
| **Secret K8s (Password)** | `kubectl create secret generic csv-loader-tls-password --from-literal=password=...` | DevOps | Secret creato |
| **Manifest K8s** | Aggiornare Deployment con variabili env + volumeMounts (vedi esempio sopra) | DevOps | Deployment aggiornato |
| **Test Swagger** | `curl -k https://<pod-ip>:8443/swagger-ui.html` | QA/Dev | UI accessibile |
| **Test API** | POST a endpoint HTTPS con certificato autofirmato accettato (`-k`) | QA/Dev | API funziona |
| **MongoDB TLS** | Aggiungere `?tls=true&tlsCAFile=...` a `mongoUri` nel JSON di richiesta | Client API | Connessione TLS |
| **Callback HTTPS** | Client POST a callback URL HTTPS; certificato auto-validato da JVM | Auto (nessuna config) | Callback ricevuto |
| **Codice Sorgente** | ❌ NON modificare | - | ✅ Zero-impact |
| **Dockerfile** | ❌ NON ricostruire | - | ✅ Immagine valida |
| **application.properties** | ❌ NON modificare | - | ✅ Spring Boot legge da env |

---

---

# CONTATTI E ESCALATION

- **Domande su certificati/PKI**: ITO / Security Team
- **Domande su Kubernetes/Secret**: DevOps / Platform Team
- **Domande su MongoDB TLS**: DBA / Database Team
- **Domande su API/Codice**: Development Team

---

**Documento versione**: 1.0  
**Ultimo aggiornamento**: 2026-11-09  
**Status**: ✅ PRONTO PER IMPLEMENTAZIONE
