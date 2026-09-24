# Kontrakt funkcjonalny - Dudu Home

Od 0.5.5 potwierdzony pusty stan właściwości producenta po zakończeniu startu Androida
zapisuje początkowy licznik zero, bez uruchamiania ani odblokowywania dodatkowej akcji.
Dopiero jego późniejszy wzrost odblokowuje Yanosika po świeżym ruchu. Nieudany odczyt
nie jest zerem. Błąd zapisu sesji blokuje Yanosika do końca procesu, nie monitoring bramy.
Instalacja 0.5.5 została potwierdzona 14 września; pełny odbiór wybudzeń nadal jest otwarty.
Od 0.5.6 kolejność gotowych akcji to brama → sprzątanie → Yanosik → Spotify.
Szczegółowy kontrakt kolejki i odtwarzania: [AUTOMATION_SEQUENCE.md](AUTOMATION_SEQUENCE.md).

## Cel

Jedna prywatnie skonfigurowana lokalizacja, jedno radio, jeden numer bramy i istniejąca rutyna
**Full Cleaning** i **Full Mop** robota Roborock QV35A. Nie budujemy uniwersalnego edytora automatyzacji.
Obecny zakres obejmuje trzy duże kafelki. Full Mop działa wyłącznie ręcznie. Spotify wznawia
muzykę na radiu po potwierdzeniu jazdy, bez nowego kafelka. Hook Yanosika opisuje [YANOSIK.md](YANOSIK.md),
a jego pasek i komunikację z tłem [PROGRESS_UI.md](PROGRESS_UI.md).

## Menu i akcje

| Sytuacja | Zachowanie |
|---|---|
| Otwarcie aplikacji | Menu „Otwórz bramę”, „Pełne sprzątanie”, „Mopowanie”; brak numeru przypomina formularzem, który można opuścić do menu |
| Rozpoznawanie GPS | Mały pasek nad obecną aplikacją albo w menu, bez przejmowania dotyku i bez akcji od odczytu stanu |
| Unieważnienie rozpoznawania | Dostępny powód przez 2 s, potem ukrycie; nowy kandydat może zastąpić wcześniej |
| Yanosik już działa | Wykryte powiadomienie usługi: bez ponownego otwarcia i bez powrotu do pulpitu |
| Yanosik bez sygnału pracy | Potwierdzony ruch, start, po 10 s dopuszczenie Spotify; bez żądania pulpitu |
| Nieznany stan Yanosika | Pominięcie startu z krótkim komunikatem; brak samoczynnego ponowienia |
| Powrót przed skrętem | „Sprawdzam trasę powrotu”, następnie potwierdzanie dojazdu; postęp z GPS, nie zegara |
| Ręczne użycie kafelka | Ekran postępu → wynik → menu |
| Automatyczne zdarzenie przy schowanej aplikacji | Ten sam ekran postępu → wynik → schowanie aplikacji; monitor pozostaje aktywny |
| Automatyczne zdarzenie przy otwartym menu | Ten sam ekran postępu → wynik → poprzednio otwarte menu |
| Formularz lub błąd czeka na użytkownika | Nie przerywamy widoku; kolejka ma limit 5 s dla bramy i 120 s dla pozostałych akcji |
| Sukces | Animacja i 5000 ms prezentacji dla bramy oraz obu rutyn |
| Informacja o blokadzie bramy | Dotychczasowe 2500 ms |
| Błąd wykonania | Pozostaje do „Ponów” albo „Zamknij”; brak samoczynnego ponowienia |
| „Ponów” | Nowa, świadoma próba ręczna; po wyniku powrót do menu |
| Roborock odrzuca autoryzację | Formularz nowego pakietu; zapis lub powrót do menu, bez powtórzenia żądania |

Wyjście z ekranu sprzątania nie wysyła żadnego polecenia zatrzymania robota. Żądanie HTTP
może nadal się kończyć. Kolejna akcja jest wtedy blokowana do zakończenia transportu.
Niepewny wynik wymaga sprawdzenia aplikacji Roborock przed ręcznym ponowieniem.

## Konfiguracja

„Ustawienia” zawiera numer bramy, dane dostępowe Roborock i informację o obecności konfiguracji
lokalizacji. GPS nie ma edytora w UI. Instalator dostarcza prywatny plik.
Dochodzi „Yanosik: wykrywanie pracy”: stan zgody i przejście do systemowego dostępu do
powiadomień. Sprawdzamy tylko pakiet i oznaczenie usługi, nie treści. Nadanie zgody nie
wykonuje akcji i nie resetuje cyklu. Brak zgody blokuje automatyczny start Yanosika, nie bramę.

- Numer: walidacja, prywatny zapis, powrót do menu. Zapis nie dzwoni i blokuje dzwonienie na
  60 sekund również po restarcie. Działa także istniejąca blokada po rozpoczęciu własnego dial.
- Roborock: jedno pole do wklejenia pakietu JSON, nie pojedynczego tokenu konta. Zapis szyfruje
  dane i wraca do menu. Bez testowego wywołania, bez resetu limitu dziennego. Formularz nie
  pokazuje zapisanego sekretu; ma ochronę zrzutu ekranu i wyłączony autofill/zapis widoku.
- Full Mop: opcjonalny `full_mop_routine_id` w tym samym prywatnym pakiecie. Bez niego kafelek
  prowadzi do informacji o brakującej konfiguracji. Nie uruchamia zastępczo Full Cleaning.
  Ręczne „Ponów” zachowuje wybraną rutynę. Full Mop nigdy nie zużywa limitu automatycznego.
- Brak telefonu nie blokuje ręcznego Roborock; brak Roborock nie blokuje bramy. Brak GPS lub
  wymaganych uprawnień wyłącza automatyzację, ale nie ręczne akcje z ich poprawną konfiguracją.
- Import działa raz. Kolejne uruchomienia nie nadpisują ręcznie zmienionych danych starym plikiem.
- Nieudany import pozostawia blokadę automatyzacji. Nie uruchamiamy częściowo skonfigurowanej automatyzacji.

## Zdarzenia lokalizacyjne i przypisanie

| Zdarzenie | Znaczenie | Akcja teraz |
|---|---|---|
| `DEPARTURE_STARTED` | Potwierdzony, trwający ruch z obszaru parkowania w kierunku bramy | Jedna próba telefonu do bramy |
| `RETURN_APPROACH` | Potwierdzony powrót przez skrzyżowanie i dojazd do punktu podejścia | Jedna próba telefonu do bramy |
| `OUTBOUND_CHECKPOINT` | Przecięcie tego samego punktu podejścia w stronę wyjazdu | Pierwsza automatyczna próba Full Cleaning w danym dniu |
| `JOURNEY_EXIT` | Dalszy wyjazd przez skrzyżowanie w dowolnym obsługiwanym kierunku | Zdarzenie diagnostyczne, bez akcji |

To są dwa różne momenty wyjazdu: brama ma być uruchomiona wcześnie, odkurzacz dopiero przy
punkcie po zewnętrznej stronie osiedla. Nie przesuwamy sprzątania do dalszego skrzyżowania.
Powrót nie uruchamia odkurzacza. Sam zapłon, restart i odzyskanie Internetu nie są zdarzeniami akcji.

Detektor toleruje sąsiednie miejsca parkingowe w obszarze 45 m od punktu odniesienia. Wymaga
świeżych i dokładnych pomiarów, potwierdzonego postoju oraz utrzymującego się ruchu w kierunku
bramy. Nie utożsamia pojedynczego skoku GPS z wyjazdem. Nie potrafi jednak zagwarantować
rozpoznania zamiaru kierowcy w każdym nieznanym manewrze - to ograniczenie GPS i małej kalibracji.

## Dokładnie co oznacza „raz dziennie”

1. Dzień to data kalendarzowa w `Europe/Warsaw`, niezależnie od strefy ustawionej w radiu.
2. Pierwszy `OUTBOUND_CHECKPOINT` rezerwuje dzień **przed** próbą uruchomienia UI lub HTTP.
3. Błąd sieci, odrzucona autoryzacja, brak danych, zajęta akcja albo zablokowane UI nie pozwalają
   spróbować automatycznie drugi raz tego dnia. To świadomie przyjęty wariant konserwatywny.
4. Rezerwacja pozostaje po restarcie aplikacji/radia, aktualizacji APK i zmianie danych dostępowych.
5. Cofnięcie daty nie umożliwia dodatkowej próby. Błędnie ustawiona przyszła data może blokować
   automat do następnej daty większej od zapisanej; ręczny kafelek pozostaje dostępny.
6. Następny dzień sam niczego nie włącza. Potrzebny jest kolejny nowy wyjazd i punkt kontrolny.
7. Ręczny kafelek i ręczne „Ponów” nie odczytują ani nie zużywają limitu dziennego. Można ich użyć
   również po wcześniejszym sprzątaniu automatycznym. Globalna blokada równoległych operacji nadal działa.

## Granice sukcesu

Brama: własny dial, obserwowane outgoing, następnie idle. Nie potwierdzamy otwarcia fizycznego.
Full Cleaning: API przyjęło polecenie rutyny. Nie sprawdzamy stanu robota przed żądaniem ani po nim.
Rutyną i ewentualnym zatrzymaniem zarządza użytkownik w aplikacji telefonu. Nie ma żadnej komendy
stop/pause/dock, automatycznego sprzątania po przywróceniu sieci ani ukrytych ponowień.
