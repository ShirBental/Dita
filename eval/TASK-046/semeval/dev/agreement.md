# Agreement (dev): annotators ['natasha', 'peterB'] + author

| field | pair | n | Cohen's kappa | PABAK |
|---|---|---|---|---|
| replaces | natasha vs peterB | 120 | 1.000 | 1.000 |
| replaces | author vs natasha | 120 | 1.000 | 1.000 |
| replaces | author vs peterB | 120 | 1.000 | 1.000 |
| sameProperty | natasha vs peterB | 120 | 0.895 | 0.900 |
| sameProperty | author vs natasha | 120 | 0.895 | 0.900 |
| sameProperty | author vs peterB | 120 | 0.964 | 0.967 |
| status | natasha vs peterB | 336 | 0.995 | 0.994 |
| status | author vs natasha | 336 | 0.995 | 0.994 |
| status | author vs peterB | 336 | 1.000 | 1.000 |
| current | natasha vs peterB | 336 | 0.990 | 0.994 |
| current | author vs natasha | 336 | 1.000 | 1.000 |
| current | author vs peterB | 336 | 0.990 | 0.994 |
| answer | natasha vs peterB | 120 | 0.735 | 0.567 |
| answer | author vs natasha | 120 | 0.129 | -0.667 |
| answer | author vs peterB | 120 | 0.133 | -0.683 |

Targets (PREREGISTRATION section 7): replaces >= 0.75, sameProperty >= 0.70, status >= 0.75, answer >= 0.80 (pending approval).

Invalid cases: []

Disagreements (8), for adjudication review and operator's audit:

- DEV-044 m2 status: {'author': 'ATTRIBUTED_STATEMENT', 'natasha': 'OFFER', 'peterB': 'ATTRIBUTED_STATEMENT'}
- DEV-075 m3>m1 sameProperty: {'author': 'yes', 'natasha': 'no', 'peterB': 'yes'}
- DEV-076 m3>m1 sameProperty: {'author': 'yes', 'natasha': 'no', 'peterB': 'yes'}
- DEV-077 m3>m1 sameProperty: {'author': 'yes', 'natasha': 'no', 'peterB': 'yes'}
- DEV-087 m3>m2 sameProperty: {'author': 'no', 'natasha': 'yes', 'peterB': 'yes'}
- DEV-106 m3>m1 sameProperty: {'author': 'yes', 'natasha': 'no', 'peterB': 'yes'}
- DEV-107 m3>m1 sameProperty: {'author': 'yes', 'natasha': 'no', 'peterB': 'yes'}
- DEV-118 m3>m2 sameProperty: {'author': 'no', 'natasha': 'no', 'peterB': 'yes'}
