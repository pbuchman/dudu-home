# Kontrakt funkcjonalny — Dudu Home

## Cel

Jedna prywatnie skonfigurowana lokalizacja, jedno radio, jeden numer bramy i istniejąca rutyna
**Full Cleaning** robota Roborock QV35A. Nie budujemy uniwersalnego edytora automatyzacji.
Obecny zakres obejmuje dwa duże kafelki. Odkurzacz jest już częścią tego zakresu; Spotify,
inne urządzenia i kolejne rutyny pozostają poza nim.

## Menu i akcje

| Sytuacja | Zachowanie |
|---|---|
| Otwarcie aplikacji | Menu „Otwórz bramę” i „Full Cleaning”; brak numeru przypomina formularzem, który można opuścić do menu |
| Ręczne użycie kafelka | Ekran postępu → wynik → menu |
| Automatyczne zdarzenie przy schowanej aplikacji | Ten sam ekran postępu → wynik → schowanie aplikacji; monitor pozostaje aktywny |
| Automatyczne zdarzenie przy otwartym menu | Ten sam ekran postępu → wynik → poprzednio otwarte menu |
| Formularz lub błąd czeka na użytkownika | Nowa automatyzacja nie przerywa tego widoku; nie ma kolejki |
| Sukces | Dotychczasowa animacja i 1350 ms prezentacji |
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

- Numer: walidacja, prywatny zapis, powrót do menu. Zapis nie dzwoni i blokuje dzwonienie na
  60 sekund również po restarcie. Działa także istniejąca blokada po rozpoczęciu własnego dial.
- Roborock: jedno pole do wklejenia pakietu JSON, nie pojedynczego tokenu konta. Zapis szyfruje
  dane i wraca do menu. Bez testowego wywołania, bez resetu limitu dziennego. Formularz nie
  pokazuje zapisanego sekretu; ma ochronę zrzutu ekranu i wyłączony autofill/zapis widoku.
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
rozpoznania zamiaru kierowcy w każdym nieznanym manewrze — to ograniczenie GPS i małej kalibracji.

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
