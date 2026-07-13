#!/usr/bin/env python3
"""Server HTTP mock per testare il callback asincrono."""
import http.server
import threading
import time

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode()
        auth = self.headers.get('Authorization', '')
        print(f'--- CALLBACK RICEVUTO ---')
        print(f'Authorization: {auth}')
        print(f'Body: {body}')
        print(f'-------------------------')
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass

server = http.server.HTTPServer(('0.0.0.0', 9999), Handler)
print('Mock callback server in ascolto su :9999 (Ctrl+C per terminare)', flush=True)
server.serve_forever()
