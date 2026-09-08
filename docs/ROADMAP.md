# Kolejka rozwoju

## Teraz: Full Mop i nowy wygląd

- Full Mop uruchamiane wyłącznie z ręcznego kafelka albo świadomego „Ponów”.
- Bez automatycznego przypisania, limitu dziennego, sprawdzania stanu lub zatrzymywania robota.
- Trzy duże ilustrowane kafelki, nowa ikona domu, spójne ekrany akcji.
- Sukces bramy i obu rutyn pokazywany przez 5 sekund; błędy nadal czekają na użytkownika.

## Następnie: Yanosik po rozpoczęciu jazdy

Zatwierdzony następny hook: otworzyć **Yanosika**, gdy GPS potwierdzi utrzymujący się ruch
samochodu przez około 10 sekund. Sam start radia, zapłon i pojedynczy skok pozycji nie wystarczają.
Praca w tle, bez formularza i bez opcji konfiguracji w aplikacji.

Ta pozycja jest kolejką, nie deklaracją działającej funkcji. Implementować po ukończeniu
powyższych zmian. Przed kodowaniem doprecyzować ponowne uzbrajanie: zwykły postój na światłach
nie powinien powodować ponownego otwierania. Zweryfikować zainstalowany pakiet/launcher,
brak Yanosika, jakość i świeżość GPS, restart radia oraz ograniczenia startu z tła.
Testy: brak startu na postoju/po samym zapłonie, pojedynczy start po potwierdzonym ruchu,
brak duplikatów podczas tej samej jazdy. Bez symulowania współrzędnych na fizycznym radiu.
