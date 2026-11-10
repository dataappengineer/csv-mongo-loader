# 🔒 HTTPS/TLS Transition - Guida Operativa (KeyStore JKS)

Guida rapida e operativa per abilitare HTTPS e TLS nell'applicazione `csv-mongo-loader`.  
L'infrastruttura utilizza il formato standard Java **JKS** (`.jks`) per la gestione di certificati server e truststore.

**Zero-Impact su codice e build**:
- ❌ **Nessuna modifica al codice sorgente Java** (`src/...`)
- ❌ **Nessuna modifica al `Dockerfile`** (non serve ricostruire l'immagine)
- ❌ **Nessuna modifica ad `application.properties`** (i parametri sono iniettati tramite variabili d'ambiente standard di Spring Boot / K8s)

---

## 📌 Sintesi: Cosa Cambia e Cosa NON Cambia

| Componente | Cosa Cambia | Azione Richiesta |
|------------|-------------|------------------|
| **Codice Java** (`src/...`) | ❌ **Nessun cambio** | Nessuna modifica al codice |
| **Dockerfile** | ❌ **Nessun cambio** | Non ricostruire l'immagine |
| **`application.properties`** | ❌ **Nessun cambio** | Parametri passati via variabili d'ambiente K8s |
| **Server Web (Spring Boot)** | 🟡 **Porta 8443 + HTTPS** | Creare Secret TLS con file `.jks` e configurare le variabili `SERVER_SSL_*` nel Pod |
| **Chiamate API (MongoDB TLS)** | 🟡 **Parametro `mongoUri`** | Aggiungere `?tls=true` nella URI inviata nel payload JSON (e TrustStore JKS se CA privata) |
| **Chiamate API (Callback HTTPS)** | 🟡 **Parametro `callbackUrl`** | Passare URL con prefisso `https://` nel payload JSON (e TrustStore JKS se CA privata) |

---

## 1. Gestione Certificati e Secret Kubernetes con JKS

### 1.1 Ispezione del KeyStore JKS (`server.jks`)

Il file `.jks` contiene il certificato del server e la corrispondente chiave privata.

Prima del deploy, verifica il file e individua il nome dell'**alias** associato al certificato (necessario per la variabile `SERVER_SSL_KEY_ALIAS`):

```bash
keytool -list -v -keystore server.jks
# Inserisci la password del KeyStore quando richiesta.
# Cerca la riga:
#   Nome alias: <tuo_alias>   (es. "server", "tomcat", "1", ecc.)
# e verifica:
#   Tipo di voce: PrivateKeyEntry
```

> **Nota sulle password**: Di norma nei KeyStore JKS la password del keystore e la password della chiave privata coincidono. Se nel tuo JKS la chiave privata ha una password differente, valorizza anche `SERVER_SSL_KEY_PASSWORD`.

---

### 1.2 (Opzionale) Generazione rapida JKS per Dev/Test locale

Se hai bisogno di generare un KeyStore JKS autofirmato per ambienti di sviluppo o collaudo:

```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -storetype JKS \
  -keystore server.jks \
  -validity 365 \
  -storepass CambiamiConPasswordSicura \
  -keypass CambiamiConPasswordSicura \
  -dname "CN=csv-mongo-loader, OU=IT, O=MyOrg, C=IT"
```

Se invece ti sono stati forniti certificato e chiave separati in formato PEM/CRT (`server-cert.pem` e `server-key.pem`), puoi assemblarli in JKS tramite un passaggio intermedio:

```bash
# 1. Crea PKCS12 temporaneo
openssl pkcs12 -export \
  -in server-cert.pem -inkey server-key.pem \
  -out temp.p12 -name server \
  -passout pass:TempPassword123

# 2. Converti in JKS definitivo
keytool -importkeystore \
  -srckeystore temp.p12 -srcstoretype PKCS12 -srcstorepass TempPassword123 \
  -destkeystore server.jks -deststoretype JKS -deststorepass CambiamiConPasswordSicura \
  -srcalias server -destalias server

# 3. Rimuovi file temporaneo
rm temp.p12
```

---

### 1.3 TrustStore JKS (`truststore.jks`) per CA Aziendale / Privata

Se il database MongoDB o l'endpoint di Callback utilizzano certificati emessi da una **CA interna aziendale** (non presente nel `cacerts` pubblico di default della JVM Java), è necessario importare il certificato pubblico della CA in un TrustStore JKS:

```bash
keytool -importcert \
  -alias enterprise-ca \
  -file ca-certificate.crt \
  -keystore truststore.jks \
  -storetype JKS \
  -storepass changeit \
  -noprompt
```

---

### 1.4 Creazione dei Secret Kubernetes

Crea i Secret nel namespace del pod:

```bash
# 1. Secret con il file JKS del server
kubectl create secret generic csv-loader-tls \
  --from-file=server.jks=./server.jks \
  -n <namespace>

# 2. Secret con la password del KeyStore
kubectl create secret generic csv-loader-tls-password \
  --from-literal=password=CambiamiConPasswordSicura \
  -n <namespace>

# 3. (OPZIONALE) Secret per il TrustStore JKS (se MongoDB o Callback usano CA privata interna)
kubectl create secret generic csv-loader-truststore \
  --from-file=truststore.jks=./truststore.jks \
  -n <namespace>
```

---

## 2. Modifiche al Deployment Kubernetes

Aggiungere al Deployment le variabili d'ambiente, i volumi e aggiornare le probe e la porta da 8080 a 8443.

### 2.1 Snippet da aggiungere al Container

```yaml
        ports:
        - name: https
          containerPort: 8443
          protocol: TCP

        env:
        # Abilita SSL/TLS in Spring Boot (Tomcat) con JKS
        - name: SERVER_SSL_ENABLED
          value: "true"
        - name: SERVER_SSL_KEY_STORE
          value: "/etc/tls/server.jks"
        - name: SERVER_SSL_KEY_STORE_TYPE
          value: "JKS"
        - name: SERVER_SSL_KEY_ALIAS
          value: "server"                               # Sostituire con l'alias verificato con keytool
        - name: SERVER_SSL_KEY_STORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: csv-loader-tls-password
              key: password
        # Opzionale: impostare solo se la password della chiave differisce da quella del keystore
        # - name: SERVER_SSL_KEY_PASSWORD
        #   valueFrom:
        #     secretKeyRef:
        #       name: csv-loader-tls-password
        #       key: key-password
        - name: SERVER_PORT
          value: "8443"

        # (OPZIONALE: solo se MongoDB o Callback usano CA privata e hai montato truststore.jks)
        # - name: JAVA_TOOL_OPTIONS
        #   value: "-Djavax.net.ssl.trustStore=/etc/tls/truststore.jks -Djavax.net.ssl.trustStoreType=JKS -Djavax.net.ssl.trustStorePassword=changeit"

        volumeMounts:
        - name: server-tls
          mountPath: /etc/tls
          readOnly: true
        # (Opzionale: montaggio truststore se in secret separato)
        # - name: truststore-tls
        #   mountPath: /etc/truststore
        #   readOnly: true

        # Probe aggiornate a HTTPS
        livenessProbe:
          httpGet:
            scheme: HTTPS
            path: /actuator/health
            port: 8443
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            scheme: HTTPS
            path: /actuator/health/readiness
            port: 8443
          initialDelaySeconds: 10
          periodSeconds: 5
```

### 2.2 Volumi del Pod

```yaml
      volumes:
      - name: server-tls
        secret:
          secretName: csv-loader-tls
          defaultMode: 0400
      # (Opzionale se presente TrustStore per CA interna)
      # - name: truststore-tls
      #   secret:
      #     secretName: csv-loader-truststore
      #     defaultMode: 0400
```

### 2.3 Service Kubernetes

Aggiornare il Service per instradare verso la porta 8443:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: csv-mongo-loader-svc
  namespace: <namespace>
spec:
  type: ClusterIP
  ports:
  - name: https
    port: 8443
    targetPort: 8443
    protocol: TCP
  selector:
    app: csv-mongo-loader
```

---

## 3. Come Usare TLS nelle Chiamate API (JSON Payload)

La struttura del JSON di richiesta (`LoadRequest`) resta invariata: cambiano solo i valori delle stringhe.

### 3.1 Connessione a MongoDB con TLS

Aggiungere il parametro `tls=true` (oppure `ssl=true`) alla query string del campo `mongoUri`:

- **Con CA pubblica valida (es. Let's Encrypt, DigiCert, MongoDB Atlas):**
  ```json
  "mongoUri": "mongodb://mongo-prod.example.com:27017/?tls=true"
  ```

- **Con CA interna / privata aziendale:**
  In ambiente Java, il driver MongoDB (`mongodb-driver-sync`) valida i certificati TLS tramite il **TrustStore JSSE** della JVM.  
  Basta impostare il flag `tls=true` nella URI:
  ```json
  "mongoUri": "mongodb://mongo-prod.example.com:27017/?tls=true"
  ```
  e fornire il TrustStore JKS al pod tramite `JAVA_TOOL_OPTIONS`:
  ```bash
  -Djavax.net.ssl.trustStore=/etc/tls/truststore.jks -Djavax.net.ssl.trustStoreType=JKS -Djavax.net.ssl.trustStorePassword=changeit
  ```
  *(Nota: il driver Java di MongoDB non supporta parametri di connessione come `tlsCAFile` presenti in altri linguaggi; la validazione avviene sempre attraverso il TrustStore JVM).*

- **Con autenticazione reciproca (mTLS - client certificate):**
  La chiave/certificato client vanno configurati a livello di JVM con:
  ```bash
  -Djavax.net.ssl.keyStore=/etc/tls/client.jks -Djavax.net.ssl.keyStorePassword=password
  ```

---

### 3.2 Callback Asincrona su HTTPS

Nel campo `callbackUrl` specificare il protocollo `https://`. Il client HTTP di Java (`java.net.http.HttpClient`) negozia TLS automaticamente e valida la CA tramite il TrustStore JVM:

```json
{
  "mongoUri": "mongodb://mongo-prod.example.com:27017/?tls=true",
  "database": "mydb",
  "collezione": "mycoll",
  "csvPath": "/data/input.csv",
  "separatore": ",",
  "enclosure": "\"",
  "modo": "TI",
  "logCollezione": "logs",
  "callbackUrl": "https://callback-service.example.com/api/notify",
  "callbackUser": "apiUser",
  "callbackPassword": "apiPassword"
}
```

---

## 4. Test & Verifica Rapida

```bash
# 1. Verifica preventiva del file JKS prima del deploy
keytool -list -v -keystore server.jks

# 2. Port-forward verso il pod nel cluster K8s
kubectl port-forward -n <namespace> svc/csv-mongo-loader-svc 8443:8443

# 3. Verifica Swagger UI su HTTPS (-k se certificato interno o autofirmato)
curl -k https://localhost:8443/swagger-ui.html

# 4. Test chiamata API POST /api/load su HTTPS
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

# 5. Ispezione del certificato esposto dal server
openssl s_client -connect localhost:8443 < /dev/null | grep -A2 "Subject:"

# 6. Controllo log del pod
kubectl logs -n <namespace> -l app=csv-mongo-loader --tail=100
```
