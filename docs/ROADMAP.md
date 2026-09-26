# Kolejka rozwoju

Bieżący etap: przygotowanie wydania 1.0.0-rc2, code 15, zainstalowanego na radiu.
Nawigacja z trzech kafelków i kolejność brama → sprzątanie → Yanosik → Spotify są w main.
Wyniki testów oraz pozostałe warunki finalnego 1.0: [RELEASE_READINESS.md](RELEASE_READINESS.md).
Poniżej wcześniejsze etapy i ograniczenia, nie deklaracja pełnego odbioru wybudzeń.

Aktualizacja lokalna 0.4: refaktoryzacja i migracja są zaimplementowane i sprawdzone
na emulatorze i radiu. Hook ruchu Yanosika przeszedł test pełnego restartu, rzeczywistego ruchu
i ostrzeżeń w tle na radiu. Integracja sygnału
wybudzenia DUDU pozostaje do wykonania po odczycie radia. Szczegóły i dokładny następny
krok: [YANOSIK.md](YANOSIK.md). Poniżej zachowano pierwotną kolejność wymagań.

## Wykonane: Full Mop i nowy wygląd

- Full Mop uruchamiane wyłącznie z ręcznego kafelka albo świadomego „Ponów”.
- Bez automatycznego przypisania, limitu dziennego, sprawdzania stanu lub zatrzymywania robota.
- Trzy duże ilustrowane kafelki, nowa ikona domu, spójne ekrany akcji.
- Sukces bramy i obu rutyn pokazywany przez 5 sekund; błędy nadal czekają na użytkownika.

## Pierwotne wymagania Yanosika (obecny stan powyżej)

Zatwierdzony następny hook: otworzyć **Yanosika**, gdy GPS potwierdzi utrzymujący się ruch
samochodu przez około 10 sekund. Sam start radia, zapłon i pojedynczy skok pozycji nie wystarczają.
Praca w tle, bez formularza i bez opcji konfiguracji w aplikacji.

Pozostała do wykonania integracja wybudzenia producenta. Zwykły postój na światłach
nie powinien powodować ponownego otwierania. Zweryfikować zainstalowany pakiet/launcher,
brak Yanosika, jakość i świeżość GPS, restart radia oraz ograniczenia startu z tła.
Testy: brak startu na postoju/po samym zapłonie, pojedynczy start po potwierdzonym ruchu,
brak duplikatów podczas tej samej jazdy. Bez symulowania współrzędnych na fizycznym radiu.
