# Kontrakt funkcjonalny - Dudu Home

## Where am I (1.2 candidate)

The first action row now has four tiles: gate, Full Cleaning, Full Mop and Gdzie jestem.
The trip screen supports explicit start/pause/resume/end, current locality/street and session
kilometres. Backgrounding keeps counting. Its wrapping location card and full-address expanded notification open
the full view when tapped. Automation presentation and execution take priority. No trip
operation dispatches a gate/robot command or resets an automation reservation.
See [the complete trip contract](WHERE_AM_I.md) and [current UI gallery](UI_GALLERY.md).

## Wczesny wyjazd - 1.3.0-rc3 / code 23

Pierwszy dobry GPS w obszarze 45 m od parkingu pozwala od razu rozpocząć wykrywanie ruchu,
także jeśli auto już jedzie. Zdarzenie `DEPARTURE_STARTED` wymaga 3 s ciągłego ruchu z
prędkością GPS co najmniej 0,7 m/s, w dowolnym kierunku, również podczas cofania. Cała
sekwencja pozostaje w obszarze wyjazdu 85 m od parkingu. Każda próbka musi mieć dokładność
do 15 m i wiek do 3 s; przerwa ponad 2 s, postój lub zły pomiar zerują odliczanie.
Nie wymagamy kierunku do bramy, minimalnego przesunięcia ani początkowego postoju 5 s.
Po ustanowieniu dobrej bazy parkingowej tego cyklu postój/zły GPS/przerwa zerują tylko
odliczanie: odzyskany dobry ruch nadal może rozpocząć 3 s wewnątrz obszaru 85 m, także
poza początkowymi 45 m. Pierwszy dobry pomiar cyklu poza 45 m ani anulowanie przed wysłaniem
nie uzyskują takiego skrótu do wymaganej nowej bazy.

Nowy pełny start systemu albo potwierdzony wzrost licznika wybudzeń producenta dopuszcza
nową okazję parkingową. Pierwszy odczyt licznika jest konserwatywną bazą, nie dodatkowym
wybudzeniem. Restart procesu, aktualizacja APK, menu, upływ blokady i przerwa GPS nie
odblokowują użytej okazji. Stan wyjazdu jest niezależny od baz Yanosika/Spotify i ręcznej
nawigacji Maps. Pierwszy dobry GPS poza parkingiem zamyka okazję startową: sam późniejszy
wjazd na parking nie uruchamia wyjazdu. Potrzebny jest rzeczywisty rozpoznany powrót i
świeży postój 5 s. Taki sam kwalifikowany postój jest wymagany po anulowaniu przed wysłaniem.

Trzy sekundy dotyczą rozpoznania zdarzenia, nie gwarantowanego czasu połączenia. Wykonawca
nadal rejestruje callbacki, zbiera snapshot telefonu i wymaga świeżego idle przed jednym
dial. Nie rozłącza istniejącej rozmowy; trwała blokada 60 s i outgoing → idle pozostają.
Od 1.5 sprzątanie następuje po sukcesie automatycznego wyjazdu. Progi powrotu i bezpieczeństwo bramy pozostają zachowane. Testy na
fizycznym DUDU7, w tym dwa rzeczywiste wybudzenia, pozostają do wykonania.


## Pierwszeństwo obszaru bramy - 1.0.0-rc3

Przed odliczaniem ruchu dla Yanosika i Spotify sprawdzamy, czy aktualna pozycja i trasa
pasują do możliwego wyjazdu do bramy lub powrotu. Jeśli tak, te aplikacje czekają na
udane zakończenie telefonu. Samo zgłoszenie, blokada, błąd ani upływ czasu nie zwalniają
tego oczekiwania. Świeży GPS potwierdzający opuszczenie obszaru pozwala przejść do zwykłego
rozpoznawania jazdy. Brak wiarygodnego GPS nie oznacza, że jesteśmy poza obszarem.
Po udanym automatycznym połączeniu przy wyjeździe (DEPARTURE_STARTED) od RC5 kolejkujemy
Yanosika i Spotify także na postoju, bez kolejnego testu ruchu. Nadal czekamy na cleanup,
koniec pięciosekundowego ekranu sukcesu, świeżą pozycję i pozostałe blokady uruchomienia.
Ręczne połączenie i powrót nie tworzą tej kontynuacji. W pozostałych przypadkach po zwolnieniu
warunku nadal wymagany jest świeży ruch przez 10 sekund i co najmniej 15 m.
Ręczne menu, wybrana nawigacja Maps i ekran wyniku zachowują pierwszeństwo.
Nie zmieniają się progi wykrywania telefonu ani warunki bezpieczeństwa połączeń.
Szczegóły i ograniczenia: [AUTOMATION_SEQUENCE.md](AUTOMATION_SEQUENCE.md).

## Manual Google Maps navigation - 1.1.0

Three fixed-order slots sit below the gate and routine actions. Import their private JSON
through Settings or the installer. Each slot holds 0..12 destinations in file order. Missing
slots show a dashed empty state, a singleton starts Maps directly, and multiple places open
a modal chooser. Three choices use the same mechanism as two; append to the private list and
reimport the complete file. X, Back and outside touch cancel without navigation. Only the
selected row requests driving navigation in Google Maps. Configuration never executes an action. A manual
Maps choice prevents pending media startup from covering it, with persistent, conservative
wake rearming. Existing gate/cleaning behavior remains unchanged. See [NAVIGATION.md](NAVIGATION.md)
for exact schema, error handling and limits. Actual guidance requires physical acceptance.

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
Obecny zakres obejmuje cztery kafelki w pierwszym rzędzie oraz trzy kafelki nawigacji. Full Mop działa wyłącznie ręcznie. Spotify wznawia
muzykę na radiu po potwierdzeniu jazdy albo udanym automatycznym połączeniu przy wyjeździe, bez nowego kafelka. Hook Yanosika opisuje [YANOSIK.md](YANOSIK.md),
a jego pasek i komunikację z tłem [PROGRESS_UI.md](PROGRESS_UI.md).

## Menu i akcje

| Sytuacja | Zachowanie |
|---|---|
| Otwarcie aplikacji | Menu „Otwórz bramę”, „Pełne sprzątanie”, „Mopowanie”, „Gdzie jestem”; brak numeru przypomina formularzem, który można opuścić do menu |
| Rozpoznawanie GPS | Mały pasek nad obecną aplikacją albo w menu; „Anuluj tę próbę” odbiera dotyk tylko w granicach paska, odczyt stanu nie uruchamia akcji |
| Unieważnienie rozpoznawania | Dostępny powód przez 2 s, potem ukrycie; nowy kandydat może zastąpić wcześniej |
| Yanosik już działa | Wykryte powiadomienie usługi: bez ponownego otwarcia i bez powrotu do pulpitu |
| Yanosik bez sygnału pracy | Potwierdzony ruch lub udane automatyczne połączenie przy wyjeździe, start, po 10 s dopuszczenie Spotify; bez żądania pulpitu |
| Nieznany stan Yanosika | Pominięcie startu z krótkim komunikatem; brak samoczynnego ponowienia |
| Powrót przed skrętem | „Sprawdzam trasę powrotu”, następnie potwierdzanie dojazdu; postęp z GPS, nie zegara |
| Ręczne użycie kafelka | Ekran postępu → wynik → menu |
| Automatyczne zdarzenie przy schowanej aplikacji | Ten sam ekran postępu → wynik → schowanie aplikacji; monitor pozostaje aktywny |
| Automatyczne zdarzenie przy otwartym menu | Ten sam ekran postępu → wynik → poprzednio otwarte menu |
| Formularz lub błąd czeka na użytkownika | Nie przerywamy widoku; kolejka ma limit 5 s dla bramy i 120 s dla pozostałych akcji |
| Sukces | Animacja i 5000 ms prezentacji dla bramy oraz obu rutyn |
| Informacja o blokadzie bramy | Dotychczasowe 2500 ms |
| Błąd wykonania ręcznego lub bramy | Pozostaje do „Ponów” albo „Zamknij”; brak samoczynnego ponowienia |
| „Ponów” | Nowa, świadoma próba ręczna; po wyniku powrót do menu |
| Ręczny Roborock odrzuca autoryzację | Formularz nowego pakietu; zapis lub powrót do menu, bez powtórzenia żądania |

Wyjście z ekranu sprzątania nie wysyła żadnego polecenia zatrzymania robota. Żądanie HTTP
może nadal się kończyć. Kolejna akcja jest wtedy blokowana do zakończenia transportu.
Niepewny wynik wymaga sprawdzenia aplikacji Roborock przed ręcznym ponowieniem.

## Anulowanie bieżącej próby

„Anuluj tę próbę” jest dostępne podczas wykrywania, oczekiwania i wykonania: w pasku,
ekranie akcji oraz powiadomieniu, gdy nakładka jest niedostępna. Jedno dotknięcie blokuje
przycisk i przerywa dalsze kroki tej próby; Yanosik i Spotify są wspólną grupą.
Anulowanie przed wysłaniem nie zużywa limitu; pozostaje prywatna historia diagnostyczna.
Automatyczne sprzątanie nie zużywa limitu także przy błędzie, pominięciu lub wygaśnięciu przed wysyłką.
Po wysłaniu pozostaje rezerwacja i komunikat „Polecenie już wysłane. Zatrzymano dalsze kroki”.
Nie oznacza to zatrzymania robota ani zamknięcia wcześniej otwartej aplikacji.

Ta sama próbka GPS nie wznawia akcji. Jazda wymaga ponownego wiarygodnego postoju przez
10 s poniżej 0,5 m/s (dokładność do 15 m, wiek do 3 s), a następnie świeżego ruchu przez
10 s i 15 m. Ponowne uzbrojenie anulowanego wyjazdu wymaga powrotu do obszaru parkowania 45 m i postoju 5 s.
Powrót wymaga opuszczenia obszaru skrzyżowania ponad 300 m przez 5 s i nowej sekwencji.
Brak dobrego GPS przerywa odliczanie. Restart nie odtwarza kolejki i wymaga świeżej bazy.
Anulowanie bramy nie omija blokady mediów w jej obszarze ani trwałej blokady dial 60 s.
Rozłączanie dotyczy wyłącznie własnego połączenia; wspólna blokada trwa do cleanup.

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
| `DEPARTURE_STARTED` | 3 s ciągłego, kwalifikowanego ruchu z okazji parkingowej w dowolnym kierunku | Jedna próba telefonu do bramy |
| `RETURN_APPROACH` | Potwierdzony powrót przez skrzyżowanie i dojazd do punktu podejścia | Jedna próba telefonu do bramy |
| `OUTBOUND_CHECKPOINT` | Przecięcie tego samego punktu podejścia w stronę wyjazdu | Tylko obserwacja trasy, bez komendy |
| `JOURNEY_EXIT` | Dalszy wyjazd przez skrzyżowanie w dowolnym obsługiwanym kierunku | Zdarzenie diagnostyczne, bez akcji |

Od 1.5 własny sukces automatycznego DEPARTURE_STARTED tworzy niezależną próbę sprzątania,
potem Yanosika i Spotify. Nie potrzeba postoju ani punktu kontrolnego. Ręczna brama i powrót
nie tworzą tej kontynuacji. Sprzątanie ma osobne anulowanie; anulowanie mediów go nie anuluje.
Błąd/pominięcie automatycznego sprzątania pokazuje komunikat przez 2,5 s, bez trwałego ekranu
błędu ani formularza. Media czekają także na cleanup. Ręczne akcje zachowują istniejące ekrany.

Detektor toleruje sąsiednie miejsca parkingowe w obszarze 45 m od punktu odniesienia. Wymaga
świeżych i dokładnych pomiarów oraz utrzymującego się ruchu przez 3 s. Początkowa okazja
nie wymaga postoju; ponowne uzbrojenie po powrocie lub anulowaniu wymaga postoju opisanego
wyżej. Nie utożsamia pojedynczego skoku GPS z wyjazdem. Nie potrafi jednak zagwarantować
rozpoznania zamiaru kierowcy w każdym nieznanym manewrze - to ograniczenie GPS i małej kalibracji.

## Dokładnie co oznacza „raz dziennie”

1. Dzień to data kalendarzowa w `Europe/Warsaw`, niezależnie od strefy ustawionej w radiu.
2. Sukces własnego automatycznego wyjazdu tworzy próbę w RAM. Trwała rezerwacja dnia powstaje
   synchronicznie dopiero przy claimSend, bezpośrednio przed wysyłką HTTP. Błąd zapisu blokuje wysyłkę.
3. Każde niewysłane pominięcie, anulowanie, wygaśnięcie lub błąd pozostawia dzień dostępny dla
   następnego rzeczywistego wyjazdu, bez retry bieżącej próby. Po dopuszczeniu wysyłki limit
   pozostaje wykorzystany także przy błędzie serwera, timeoutcie lub niepewnym wyniku.
4. Rezerwacja pozostaje po restarcie aplikacji/radia, aktualizacji APK i zmianie danych dostępowych.
5. Cofnięcie daty nie umożliwia dodatkowej próby. Błędnie ustawiona przyszła data może blokować
   automat do następnej daty większej od zapisanej; ręczny kafelek pozostaje dostępny.
6. Następny dzień sam niczego nie włącza. Potrzebny jest kolejny nowy automatyczny wyjazd zakończony sukcesem bramy.
   Sprawdzamy dzień także przy wysyłce; zadanie ze starego dnia wygasa. Stary last_attempt_day zachowujemy konserwatywnie.
7. Ręczny kafelek i ręczne „Ponów” nie odczytują ani nie zużywają limitu dziennego. Można ich użyć
   również po wcześniejszym sprzątaniu automatycznym. Globalna blokada równoległych operacji nadal działa.

## Granice sukcesu

Brama: własny dial, obserwowane outgoing, następnie idle. Nie potwierdzamy otwarcia fizycznego.
Full Cleaning: API przyjęło polecenie rutyny. Nie sprawdzamy stanu robota przed żądaniem ani po nim.
Rutyną i ewentualnym zatrzymaniem zarządza użytkownik w aplikacji telefonu. Nie ma żadnej komendy
stop/pause/dock, automatycznego sprzątania po przywróceniu sieci ani ukrytych ponowień.
