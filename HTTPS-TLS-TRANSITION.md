# 🔒 HTTPS/TLS Transition - Guida Operativa

Guida rapida e operativa per abilitare HTTPS e TLS.  
**Nessuna modifica al codice sorgente né al Dockerfile** (Zero-Impact): le modifiche sono interamente confinate ai manifest Kubernetes e ai parametri delle richieste JSON.

---

## 📌 Sintesi: Cosa Cambia e Cosa NON Cambia

| Componente | Cosa Cambia | Azione Richiesta |
|------------|-------------|------------------|
| **Codice Java** (`src/...`) | ❌ **Nessun cambio** | Nessuna modifica al codice |
| **Dockerfile** | ❌ **Nessun cambio** | Non ricostruire l'immagine |
| **`application.properties`** | ❌ **Nessun cambio** | Parametri passati via variabili d'ambiente K8s |
| **Server Web (Spring Boot)** | 🟡 **Porta 8443 + HTTPS** | Creare Secret TLS e aggiungere variabili `SERVER_SSL_*` nel Pod |
| **Chiamate API (MongoDB TLS)** | 🟡 **Parametro `mongoUri`** | Aggiungere `?tls=true` nella URI inviata nel payload JSON |
| **Chiamate API (Callback HTTPS)** | 🟡 **Parametro `callbackUrl`** | Passare URL con prefisso `https://` nel payload JSON |

---

## 1. Preparazione Certificati e Secret K8s

### 1.1 Certificato Server (PKCS12)
Tomcat richiede il certificato server in formato PKCS12 (`server.p12`).

```bash
# Se hai già certificato (server-cert.pem) e chiave (server-key.pem) da CA/ITO:
openssl pkcs12 -export \
  -in server-cert.pem -inkey server-key.pem \
  -out server.p12 -name server \
  -passout pass:CambiamiConPasswordSicura

# (OPZIONALE - Solo per Dev/Test locale) Generazione certificato autofirmato:
openssl req -x509 -newkey rsa:2048 -days 365 -nodes \
  -keyout server-key.pem -out server-cert.pem \
  -subj "/CN=csv-mongo-loader/O=MyOrg/C=IT"

openssl pkcs12 -export \
  -in server-cert.pem -inkey server-key.pem \
  -out server.p12 -name server \
  -passout pass:CambiamiConPasswordSicura
```

### 1.2 Creazione Secret Kubernetes
Crea i Secret nel namespace del pod:

```bash
# 1. Secret con il file PKCS12
kubectl create secret generic csv-loader-tls \
  --from-file=server.p12=./server.p12 \
  -n <namespace>

# 2. Secret con la password del keystore
kubectl create secret generic csv-loader-tls-password \
  --from-literal=password=CambiamiConPasswordSicura \
  -n <namespace>

# 3. (OPZIONALE) Secret con CA aziendale se MongoDB o Callback usano CA privata:
kubectl create secret generic ca-bundle \
  --from-file=ca.pem=./ca-certificate.pem \
  -n <namespace>
```

---

## 2. Modifiche al Deployment Kubernetes

Aggiungere al Deployment le variabili d'ambiente, i volumi e aggiornare le probe e la porta (da 8080 a 8443).

### 2.1 Snippet da aggiungere al Container

```yaml
        ports:
        - name: https
          containerPort: 8443
          protocol: TCP

        env:
        # Abilita SSL/TLS in Spring Boot / Tomcat
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

        # (Solo se la Callback usa CA interna aziendale autofirmata)
        # - name: JAVA_TOOL_OPTIONS
        #   value: "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"

        volumeMounts:
        - name: server-tls
          mountPath: /etc/tls
          readOnly: true
        # (Opzionale, solo se serve montare CA interna per MongoDB o Callback)
        # - name: ca-bundle
        #   mountPath: /etc/ssl/mongo
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
      # (Opzionale se presente CA aziendale)
      # - name: ca-bundle
      #   secret:
      #     secretName: ca-bundle
      #     defaultMode: 0444
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

Non cambia la struttura del JSON di richiesta (`LoadRequest`), ma solo i valori delle stringhe.

### 3.1 Connessione a MongoDB con TLS

Basta aggiungere i parametri TLS alla query string del campo `mongoUri`:

- **CA pubblica valida (es. Let's Encrypt / DigiCert / MongoDB Atlas):**
  ```json
  "mongoUri": "mongodb://mongo-prod.example.com:27017/?tls=true"
  ```
- **CA interna / autofirmata (con certificato CA montato nel pod):**
  ```json
  "mongoUri": "mongodb://mongo-prod.example.com:27017/?tls=true&tlsCAFile=/etc/ssl/mongo/ca.pem"
  ```
- **mTLS (con certificato client):**
  ```json
  "mongoUri": "mongodb://mongo-prod.example.com:27017/?tls=true&tlsCAFile=/etc/ssl/mongo/ca.pem&tlsCertificateKeyFile=/etc/ssl/mongo/client.pem"
  ```

### 3.2 Callback Asincrona su HTTPS

Nel campo `callbackUrl` basta specificare il protocollo `https://`. Il client Java negozia TLS automaticamente:

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
# 1. Port-forward verso il pod
kubectl port-forward -n <namespace> svc/csv-mongo-loader-svc 8443:8443

# 2. Verifica Swagger UI HTTPS (-k se certificato autofirmato/interno)
curl -k https://localhost:8443/swagger-ui.html

# 3. Test chiamata API POST /api/load
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

# 4. Verifica certificato esposto dal server
openssl s_client -connect localhost:8443 < /dev/null | grep -A2 "Subject:"

# 5. Controllo log del pod
kubectl logs -n <namespace> -l app=csv-mongo-loader --tail=100
```
