# Guida Operativa TLS/JKS - Cluster Rancher (Namespace: apilayer)

Guida specifica per applicare la transizione HTTPS/TLS al modulo **csv-mongo-loader** sui manifest reali di Rancher (`apilayer`).

---

## 0. Prerequisiti: Generazione Certificato JKS e Creazione Secret K8s

Eseguire questi comandi **sul proprio terminale locale (WSL / Linux)** dove sono installati `keytool` (Java) e `kubectl`:

### 0.1 Generare il file `server.jks` da zero

Eseguire nella cartella di lavoro corrente:

```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -storetype JKS \
  -keystore server.jks \
  -validity 730 \
  -storepass PasswordSicura123! \
  -keypass PasswordSicura123! \
  -dname "CN=csv.loader.dss.local, OU=DSS, O=RegionePuglia, C=IT" \
  -ext "SAN=dns:csv.loader.dss.local,dns:csv-loader-svc,dns:localhost"
```

> **Dettagli del comando:**
> - Genera il file fisico **`server.jks`** nella cartella locale corrente.
> - L'alias della chiave è impostato su **`server`** (da usare nella variabile `SERVER_SSL_KEY_ALIAS`).
> - Il certificato include il dominio effettivo dell'ingress (`csv.loader.dss.local`), il service interno (`csv-loader-svc`) e `localhost`.
> - Sostituire `PasswordSicura123!` con la password concordata con il cliente.

---

### 0.2 Creare i Secret Kubernetes nel namespace `apilayer`

Dalla **stessa cartella locale** in cui si trova il file `server.jks` appena creato, eseguire:

```bash
# 1. Secret contenente il file JKS
kubectl create secret generic csv-loader-tls \
  --from-file=server.jks=./server.jks \
  -n apilayer

# 2. Secret con la password del keystore JKS
kubectl create secret generic csv-loader-tls-password \
  --from-literal=password="PasswordSicura123!" \
  -n apilayer
```

*(In alternativa tramite interfaccia grafica Rancher: andare nel namespace `apilayer` > **Storage > Secrets** e creare i due secret inserendo rispettivamente il file con chiave `server.jks` e il testo della password con chiave `password`).*

---

## 1. Modifica al `deployment.yaml`

Nel manifest del Deployment `csv-mongo-loader` (namespace `apilayer`):

### Differenze da applicare:
1. Cambiare la porta da `8080` a `8443`.
2. Aggiungere le variabili `SERVER_SSL_*` e aggiornare `SERVER_PORT`.
3. Aggiungere il `volumeMounts` `/etc/tls`.
4. Aggiungere il `volume` del secret `csv-loader-tls`.

### YAML Container & Pod aggiornato:

```yaml
    spec:
      containers:
        - env:
            - name: SERVER_PORT
              value: '8443'
            - name: SERVER_SSL_ENABLED
              value: 'true'
            - name: SERVER_SSL_KEY_STORE
              value: '/etc/tls/server.jks'
            - name: SERVER_SSL_KEY_STORE_TYPE
              value: 'JKS'
            - name: SERVER_SSL_KEY_ALIAS
              value: 'server'   # <-- Sostituire con l'alias effettivo del JKS
            - name: SERVER_SSL_KEY_STORE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: csv-loader-tls-password
                  key: password
          image: >-
            oros-git.regione.puglia.it:5050/dss-crm-puglia/dss/csv-mongo-loader:v1.3
          imagePullPolicy: IfNotPresent
          name: csv-mongo-loader
          ports:
            - containerPort: 8443
              protocol: TCP
          resources: {}
          terminationMessagePath: /dev/termination-log
          terminationMessagePolicy: File
          volumeMounts:
            - mountPath: /data/in
              name: csv-volume
            - mountPath: /etc/tls
              name: server-tls
              readOnly: true
      dnsPolicy: ClusterFirst
      imagePullSecrets:
        - name: oros-git-new-registry
      restartPolicy: Always
      schedulerName: default-scheduler
      securityContext: {}
      terminationGracePeriodSeconds: 30
      volumes:
        - name: csv-volume
          persistentVolumeClaim:
            claimName: csv-pvc
        - name: server-tls
          secret:
            secretName: csv-loader-tls
            defaultMode: 0400
```

---

## 2. Modifica al `service.yaml` (`csv-loader-svc`)

Nel Service `csv-loader-svc`, cambiare il `targetPort` da `8080` a `8443`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: csv-loader-svc
  namespace: apilayer
spec:
  ports:
    - name: https
      port: 8089
      protocol: TCP
      targetPort: 8443
  selector:
    app: csv-mongo-loader
  sessionAffinity: None
  type: ClusterIP
```

> **Nota**: `port: 8089` rimane invariato, quindi chi chiama il service dall'interno del cluster o tramite Ingress non deve cambiare porta.

---

## 3. Modifica all'`ingress.yaml` (`csv-mongo-loader-ingress`)

Dato che il backend comunica ora in HTTPS, l'Ingress (Traefik) deve inoltrare la richiesta con schema `https`.

Aggiungere l'annotazione per Traefik `serversscheme: https` (e aggiornare quella nginx per coerenza):

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: csv-mongo-loader-ingress
  namespace: apilayer
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: websecure
    traefik.ingress.kubernetes.io/service.serversscheme: https
    nginx.ingress.kubernetes.io/backend-protocol: HTTPS
spec:
  ingressClassName: traefik
  rules:
    - host: csv.loader.dss.local
      http:
        paths:
          - backend:
              service:
                name: csv-loader-svc
                port:
                  number: 8089
            path: /
            pathType: Prefix
```

---

## 4. Verifica Finale Post-Deploy

Dalla rete interna o tramite pod di debug:

```bash
# Test Swagger UI attraverso l'ingress o il service
curl -k https://csv.loader.dss.local/swagger-ui.html

# Controllo log del pod avviato
kubectl logs -n apilayer -l app=csv-mongo-loader --tail=50
```
Nei log di Spring Boot deve comparire:
`Tomcat started on port(s): 8443 (https)`
