#!/usr/bin/env python3
import random
import string

cities = ['Roma','Milano','Napoli','Torino','Bologna','Firenze','Venezia','Palermo']
print('id_chiave,nome,cognome,eta,citta,codice_fiscale,email')
for i in range(1, 50001):
    nome = 'Nome' + str(i)
    cognome = 'Cognome' + str(i)
    eta = random.randint(18, 80)
    citta = random.choice(cities)
    cf = ''.join(random.choices(string.ascii_uppercase, k=6)) + ''.join(random.choices(string.digits, k=10))
    email = f'user{i}@example.com'
    print(f'{i},{nome},{cognome},{eta},{citta},{cf},{email}')
