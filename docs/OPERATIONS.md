# Instalacja, konfiguracja i odzyskiwanie

## Read-only failure capture before opening or updating the app

Resolve an owner-only `PRIVATE_DIR` outside every Git repository (0700). With the existing
authorized radio connection, collect to a new directory whose parent already exists:

```sh
python3 scripts/automation-diagnostics.py collect --serial DEVICE_SERIAL \
  --output "$PRIVATE_DIR/new-capture"
python3 scripts/automation-diagnostics.py summarize "$PRIVATE_DIR/new-capture"
```

The tool only checks the existing ADB device state, reads app-private `diagnostics.txt` and
its previous file via `run-as`, and captures allowlisted service dump fields. It never
starts/stops/installs the app or reads configuration, firmware logcat or the Routebook
database. It creates a 0700 capture directory and 0600 journal, monitor and summary files;
an existing output directory is rejected. Console output is category-only. Keep all captures
private, including coarse location-quality and action timing evidence.

The summary groups process/attempt timelines with original observation wall/elapsed times,
current departure rejection/GPS buckets and cycle/reservation state. Bounded retention,
dropped asynchronous rows and process death can leave gaps: absence of a row is not proof
that no action occurred. Read the complete correlation before blaming GPS, detector, queue
or phone IPC. See STARTUP_DIAGNOSTICS.md. Missing hardware blocks capture; do not open/reset
the app merely to produce a success result.

## Current normal update: one map, no backups

The normal updater creates no APK/data/map backup, sidecar manifest/audit or saved installation
receipt, and performs no automatic rollback. Historical backup evidence below describes earlier
runs. Namespace migration in MIGRATION.md is a separate operation.

Build the signed local APK and reuse the existing `$PRIVATE_DIR/maps/places.sqlite`. Rebuild
the map only when changing its OSM data, schema or heading policy; APK-only updates do not
rebuild it. Rebuilding changes generation metadata/bytes and therefore requires a transfer.
Use the current full private navigation source. Inputs stay owner-only outside repositories.
The builder keeps schema/source/date/policy metadata and heading source proof inside SQLite.
Existing schema2/3 packs are validated directly without a companion file. Schema2 requires
code23+; schema3 requires code29+. APK/map compatibility and the supplied APK signature are
checked before mutation; Android `install -r` enforces replacement signing identity. Never
uninstall or downgrade to bypass a rejection.

```sh
python3 scripts/install-radio.py --config "$PRIVATE_DIR/config.json" \
  --roborock "$PRIVATE_DIR/roborock/routine-credentials.json" \
  --navigation "$DUDU_CURRENT_NAVIGATION" --map "$PRIVATE_DIR/maps/places.sqlite" \
  --apk app/build/outputs/apk/debug/app-debug.apk --adb "$DUDU_ADB"
```

Repeat `--adb` for another existing server wrapper. Discovery uses existing authorization,
cached private endpoint and mDNS; no pairing or broad LAN scan. Missing/ambiguous devices stop.
It compares installed geometry/navigation and preserves the actual enabled state. Audio mode,
visible UI and service state provide repeated conservative idle observations; they do not prove
fresh vendor HFP idle. Unknown/busy state prevents stopping the app. After the first idle check,
maintenance/install-verification block new admission; a second idle check waits for any existing
cleanup. Only then does it force-stop and compare safety/session state in memory.

The October19 radio path reads the existing Wispr steering adapter's permission-protected
`ProbeReceiver` DUMP into its private redacted `window-tree.json`. It checks a connected
service, a fresh device-monotonic timestamp, exact node counts and complete child/root trees.
Missing/disconnected/stale/truncated probes block the operation; there is no UIAutomator
fallback or accessibility-setting change. A running Dudu process requires explicit
`action_busy=false`; a missing service is accepted only with a successful parsed process
listing proving no Dudu process. These observations still cannot certify vendor HFP idle.

Equal map SHA-256 skips transfer and preserves map mtime. **Changed/missing/corrupt remote
maps currently block before overwrite or SQLite sidecar cleanup.** The admission marker is
not a map-reader lock: a notification listener/task can restart the process, and monitor/trip
construction is not universally guarded before map access. Process-absence checks surround
equal-map cleanup, but cannot enforce exclusion across a large transfer. That stronger gate
must be implemented and verified before direct replacement/repair is enabled. No `.new`,
previous or second map is made. Local malformed maps
are rejected before touching the radio. Input/managed directory symlinks are refused. Cleanup
only removes the exact recognized `places.*` historical artifact names in `radio_update.py`
from the selected map parent and radio `no_backup`; it never searches other directories or
infers ownership from extensions. For a one-time migration, explicitly select each old managed map or backup directory:

```sh
python3 scripts/cleanup-update-artifacts.py "$PRIVATE_DIR/maps"
python3 scripts/cleanup-update-artifacts.py "$PRIVATE_DIR/radio-backups"
```

This nonrecursive helper removes old maps only when an old builder manifest, computed hash,
actual database metadata/counts and any bound heading-audit hash all agree. It preserves
canonical `places.sqlite`. Historical `before-update-*` directories require exactly the known
files, matching SHA manifest and a recognized optional receipt. All candidates are inspected
before deletion; symlinks/path escapes/unexpected backup contents reject the run. Unrelated
files and unproven map versions remain untouched. No owner cleanup was run during development.

The persistent install-verification block survives successful import until configuration,
full navigation, daily quota/dial cooldown/journey/departure/overlay preferences, trip state,
APK/map hashes and effective permissions pass verification. Then the wrapper releases it,
starts monitoring and checks the visible passive trip resource/control structure. The probe
redacts Dudu strings, so Start/Pauza/Reset/time text is not verified by this smoke. Existing
trip sessions may resume counting.
Any failure retains/recreates the block, with no automatic launch, restore, rollback or data
clearing. It never force-stops a call that may have begun after release. Repeat the same command
with valid inputs to finish after an already-consumed import when the map already matches.
Map repair remains blocked pending the enforced quiescence gate described above.
Do not remove the block manually to open an incomplete map.

The script prints a sanitized result and saves no receipt. A successful local/emulator update
is not physical-radio acceptance. This change performed no physical installation, driving/wake
check or cleanup of owner archives. The older national-map and RC3 evidence remains historical.


## Historycznie potwierdzona instalacja 1.1.0

27 września zainstalowano code 18 z kopią APK/danych, kontrolą podpisu i zgodności SHA-256.
Import prywatnego pełnego pliku potwierdził układ 1/3/1. Pierwsze dwa cele grupy, inne sloty,
tryby nawigacji, numer bramy, dzienny limit i stan sesji zachowano. Na radiu sprawdzono
napis „3 miejsca”, trzy wiersze i wybór trzeciego: Maps uruchomiły prowadzenie i pokazały
właściwy cel oraz adres. Dowody i konfiguracja pozostają prywatne.

Aby dopisać kolejne miejsce, użyj pełnego aktualnego navigation.json poza Git i dopisz element
w istniejącym destinations. Zachowaj kolejność i inne sloty. Zweryfikuj plik, zaimportuj go
ponownie i sprawdź cel w Maps. Sam zapis na komputerze nie zmienia radia. Nie trzeba zmieniać
kodu ani budować APK, jeśli działa code 17 lub nowszy. Dokładna procedura i syntetyczny przykład
trzech miejsc są w [NAVIGATION.md](NAVIGATION.md). Uaktualnij prywatny wskaźnik pliku źródłowego,
aby późniejsza instalacja nie przywróciła starej listy.

## Historyczna instalacja 1.0.0-rc4

27 września zainstalowano code 17 z prywatną kopią APK/danych i kontrolą podpisu oraz SHA-256.
Import schema 2 i układ slotów 1/2/1 przeszły weryfikację. Ustawienia bramy, limit dzienny i
stan sesji zachowano. Menu, dwie pozycje modalu i zamykanie sprawdzono na fizycznym radiu.
Bieżącą trasę Maps zachowano; test nowych celów na radiu pozostaje otwarty. Dowody są prywatne.
Pełne wyniki i nadal otwarte próby: [VERIFICATION.md](VERIFICATION.md).

## Historyczny odbiór kandydata 1.0.0-rc2

Poprzednia potwierdzona instalacja to 1.0.0-rc2/code 15 z 26 września. Opcjonalny tryb adresowy
jednego celu poprawnie wyświetlił jego zapisaną etykietę w Maps; pozostałe sloty nie zmieniły się.
Wcześniejsza aktualizacja rc1 z kopią
zapasową, import prywatnych danych i start prowadzenia z każdego z trzech kafelków przeszły
weryfikację na radiu. Pełny odbiór wybudzeń i historyczne testy przejazdów pozostają otwarte.
Najpierw pobierz logi bez otwierania aplikacji, potem wykonaj procedurę aktualizacji poniżej.
Sprawdź bieżącą wersję i podpis; nie nadpisuj nowszej wersji ani nie czyść danych.
Istniejąca zgoda obserwatora powiadomień służy teraz również Spotify.

Pierwsza próba: pełny restart, bez ręcznego startu Spotify, potwierdzony ruch, Yanosik,
następnie Spotify i rzeczywisty dźwięk na radiu. Brak sterowalnej sesji jest ograniczeniem,
nie zaliczonym testem. Następnie sprawdź już działającego Yanosika, priorytety bramy i
sprzątania, ochronę ręcznego menu, prawdziwe uśpienie/wybudzenie oraz krótki postój.
Szczegóły i granice: [AUTOMATION_SEQUENCE.md](AUTOMATION_SEQUENCE.md).

Wersja lokalna 0.4 zmienia identyfikator na `com.pbuchman.duduhome`. Przejście ze starej
instalacji wymaga [MIGRATION.md](MIGRATION.md), nie komendy aktualizacji poniżej.
Nowy komponent menu to `.ui.MainActivity`, wybudzenia `.startup.HomeWakeActivity`.
Konfiguracja zadania DUDU jest konieczna po vendorowym force-stop. Procedura i status testów
w [STARTUP_DIAGNOSTICS.md](STARTUP_DIAGNOSTICS.md); ograniczenia w [YANOSIK.md](YANOSIK.md).

## Zasady dla operatora i kolejnego agenta

Pracuj w tym repozytorium, nie w prywatnym poprzedniku. Nie potrzebujesz starej sesji czatu:
kontrakt jest w [FUNCTIONAL.md](FUNCTIONAL.md), technika w [ROBOROCK.md](ROBOROCK.md),
aktualne dowody w [VERIFICATION.md](VERIFICATION.md). Nigdy nie zakładaj, że historyczny numer,
adres ADB lub pakiet poświadczeń nadal są poprawne. Nie wyświetlaj sekretów w terminalu/chacie.

Aktualizacja radia wymaga: żadnej trwającej rozmowy ani akcji,
autoryzowane ADB oraz przygotowane prywatne pliki. Nie odinstalowuj aplikacji i nie czyść danych.
Testy fizycznych akcji wykonujemy dopiero w aplikacji zainstalowanej na radiu - nie z komputera.

## Prywatny katalog

`PRIVATE_DIR` wskazuje katalog poza wszystkimi repozytoriami (0700); pliki mają 0600.
W instalacji właściciela jest to sąsiedni katalog `dudu-home-private`. Jego
`CURRENT_IMPLEMENTATION.md` wskazuje lokalne archiwum, konfigurację i ostatni stan bez potrzeby
odtwarzania czatu. Nie kopiuj zawartości do Git ani publicznych issue.

```text
PRIVATE_DIR/
  config.json                       # prywatny numer, GPS, źródła i instalacyjne metadane
  roborock/routine-credentials.json  # minimalny pakiet do wykonania Full Cleaning
  navigation.json                   # trzy prywatne sloty/listy, schemat w NAVIGATION.md
  maps/                            # one final places.sqlite; no update backups
  archive-.../                      # oryginalny projekt, APK, podpis i pełne logi kalibracji
  replay/                          # prywatne dane wejściowe detektora
```

Archiwów nie usuwamy. Pierwotne osiem przejazdów jest zachowane poza repozytorium wraz z sumami
kontrolnymi i audytem. Dane historyczne są opisane oddzielnie od odczytanych z aktualnego radia.
Google Password Manager **nie jest wykonanym backupem**: poprzednia próba automatycznego zapisu
została zablokowana przez zabezpieczenia przeglądarki. Nie obchodź tej blokady. Użytkownik może
wykonać dodatkowy zapis ręcznie; działanie aplikacji nie zależy od tego menedżera.

## Format konfiguracji

Publiczny `config.example.json` dokumentuje nazwy pól, nie jest gotowy do jazdy.
W prywatnej kopii uzupełnij cztery punkty albo ustaw `points: null` dla trybu wyłącznie ręcznego.
Współrzędne są w stopniach WGS84; punkt parkingu jest odniesieniem, a nie jedynym dopuszczalnym miejscem.

| Pole | Kontrakt |
|---|---|
| `schema_version` | Instalator przyjmuje historyczne 1 i aktualne 2; do aplikacji wysyła 2 |
| `gate_number` | Poprawny, znormalizowany numer lub null; null zachowuje istniejący numer |
| `gate_number_verified_on_current_device` | Nie wystarczy historyczne true: instalator porówna numer z bieżącymi preferencjami radia |
| `points.parking` | Środek obszaru parkowania |
| `points.gate` | Brama; RC3 nie wymaga ruchu w jej kierunku dla wczesnego wyjazdu |
| `points.approach` | Punkt otwierania przy powrocie i uruchamiania sprzątania przy wyjeździe |
| `points.junction` | Skrzyżowanie identyfikujące właściwy powrót i dalszy wyjazd |
| `automation_enabled` | Instalator ustala wartość na podstawie jawnej flagi `--enable-automation`, nie ufając zapisanej starej wartości |
| `roborock` | Minimalny pakiet z [ROBOROCK.md](ROBOROCK.md), zwykle dostarczany oddzielnym plikiem przez `--roborock`; null zachowuje istniejący |

Sekrety nie są Gradle properties, BuildConfig, zasobami ani argumentami ADB. Instalator przesyła
zawartość przez stdin. Ścieżki plików mogą być argumentami; ich zawartość nie.

## Przygotowanie i aktualizacja

1. Sprawdź `git status`, aktywną gałąź i [VERIFICATION.md](VERIFICATION.md). Zbuduj
   `./gradlew assembleDebug assembleDebugAndroidTest lintDebug`. Zachowaj prywatny klucz podpisu.
2. Odczytaj aktualny stan ADB. Jeśli brak radia, zakończ możliwe prace lokalne; nie zaznaczaj
   instalacji lub testów jako wykonanych. Historyczny adres może się zmienić.
3. Uruchom walidację bez kontaktu z radiem:

   ```sh
   python3 scripts/configure-device.py DEVICE_SERIAL "$PRIVATE_DIR/config.json" \
     --roborock "$PRIVATE_DIR/roborock/routine-credentials.json" --enable-automation --dry-run
   ```

   To sprawdza format, zakres geometrii i dozwolony endpoint; **nie** sprawdza ważności autoryzacji,
   numeru z urządzenia, fizycznej lokalizacji ani podpisu zainstalowanego APK.

4. Pełną aktualizację APK/mapy wykonaj przez `install-radio.py` z bieżącej procedury powyżej.
   Nie dodawaj katalogu backupu. Instalator zachowuje dane aplikacji i sprawdza je przed odblokowaniem.
5. Sama wymiana konfiguracji w już zgodnym APK używa `configure-device.py` bez `--apk`:

   ```sh
   python3 scripts/configure-device.py DEVICE_SERIAL "$PRIVATE_DIR/config.json" \
     --roborock "$PRIVATE_DIR/roborock/routine-credentials.json" \
     --navigation "$PRIVATE_DIR/navigation.json" --enable-automation
   ```

   Porównuje numer z urządzeniem, czeka na idle, zakłada maintenance i atomowo zapisuje
   jednorazowy import. Nie zmienia bieżącego pliku konfiguracji tylko po to, by zatrzymać automat.
   Bez `--enable-automation` docelowa konfiguracja celowo wyłącza automat; to jawna zmiana konfiguracji.
6. Otwórz menu, aby zużyć inertny import. Sprawdź sekcje i uprawnienia bez wypisywania wartości.
   Import usuwa własny maintenance, zachowuje zewnętrzny install-verification, jeśli taki istnieje.
   Niskopoziomowe `configure-device.py --apk` zawsze zostawia install-verification; pełne
   aktualizacje APK kończy `install-radio.py`, który weryfikuje stan przed odblokowaniem.
   Dzienny limit, cooldown, sesja podróży i stan przejazdu pozostają zachowane. W razie błędu
   popraw wejście i ponów import; nie kasuj danych ani blokad na ślepo.
7. Autostart DUDU powinien wskazywać `.startup.HomeWakeActivity`; BOOT_COMPLETED nie potwierdza
   vendorowego wybudzenia. Próby fizyczne i świeży GPS pozostają osobną weryfikacją.

Świeża instalacja dla innego użytkownika: `scripts/install-on-device.sh DEVICE_SERIAL`, potem
formularz numeru na postoju. Helper odmawia aktualizacji już istniejącej aplikacji. Dla tego
radia używamy aktualizacji z kopią, nie ścieżki świeżej instalacji. Nie uruchamiaj baseline'u
automatycznie po instalacji - stary baseline po starcie z zapisanym numerem dzwoni.

Helper świeżej instalacji wymaga dokładnie jednego jawnego DEVICE_SERIAL. Przed buildem
odczytuje listę pakietów, również tych z zachowanymi danymi (`pm list packages -u`). Błąd ADB,
pusta lista lub nierozpoznany format przerywają pracę, zamiast oznaczać brak aplikacji.
Instalacja nie używa `-r`: pojawienie się pakietu po sprawdzeniu nie pozwala na jego cichą
aktualizację. Nie ma automatycznego ponowienia ani uruchomienia aplikacji. Regresję sprawdza
`python3 scripts/test-fresh-installer.py` na atrapach ADB/Gradle, bez operacji na urządzeniu.

## Minimalna sesja akceptacyjna na radiu

Dla 0.5.5 konieczny jest pełny restart, zapis VENDOR_BASELINE_ZERO, pierwsza jazda,
rzeczywiste uśpienie i pierwsze wybudzenie z VENDOR_WAKE_REARM CYCLE=1. Dopiero świeży ruch
ma wywołać jedną próbę Yanosika, o ile nie działa on już wcześniej. Powtórzyć cykl 2 oraz
krótki postój bez zmiany licznika. Nie naprawiać historycznego braku bazy przez kasowanie
preferencji. Brak zgody/połączenia obserwatora powiadomień pozostaje pominięciem bez ponowień.
Szczegóły protokołu i jawne ograniczenia są w STARTUP_DIAGNOSTICS.md.

Od 0.5.2 automatyczny start Yanosika wymaga zgody w „Ustawienia > Yanosik: wykrywanie pracy”.
Nadaj dostęp systemowy i sprawdź odczyt, nie tylko sam przełącznik. Zweryfikuj stan Yanosika
uruchomionego wcześniej, jego brak oraz cofnięcie zgody: odpowiednio zero startów/HOME,
jeden start/HOME po ruchu oraz pominięcie. Samo nadanie zgody nie rozpoczyna akcji.
Przed instalacją podczas następnej sesji najpierw zabezpiecz logi nieregularnego braku startu.
Problem występował także po krótkich i średnich postojach. Sprawdź różne rzeczywiste cykle
zapłonu; pełny restart systemu nie zastępuje testu wybudzenia producenta.

Zanim kierowca ruszy, operator ma potwierdzony dostęp do logów i gotowość konfiguracji.
Podczas jazdy kierowca nie obsługuje UI ani terminala. Nie potrzeba kolejnych ośmiu tras.

1. **Na postoju:** otwórz menu. Zapis numeru/formularza nie wywołuje akcji. Jeśli numer był
   zmieniany, zrestartuj aplikację w ciągu 60 s i sprawdź blokadę bez dial. Nie podmieniaj
   prawdziwej autoryzacji na fikcyjną tylko po to, żeby wymuszać błąd na chmurze.
2. **Brama ręczna:** po cooldown wybierz kafelek. W logach własny dial → outgoing → hangup → idle;
   ekran wraca do menu. Jedna próba. Przy istniejącej rozmowie aplikacja ma odmówić i jej nie rozłączyć.
3. **Full Cleaning ręczne:** kafelek na radiu, jedno żądanie, wynik, powrót do menu. Potwierdź
   uruchomienie właściwej rutyny w telefonie. **Nie zatrzymuj automatycznie sprzątania.** Użytkownik
   decyduje w aplikacji Roborock, co dalej. Ręczny start nie zużywa limitu automatycznego.
4. **Wyjazd:** z zaparkowanego auta uruchom radio i silnik. Sam start niczego nie wywołuje.
   Potwierdź dobry GPS w obszarze parkowania, rusz w dowolnym kierunku, także cofając:
   po 3 s kwalifikowanego ciągłego ruchu powstaje zdarzenie jednej próby bramy. Snapshot i
   świeży idle telefonu nadal poprzedzają dial, więc czas zdarzenia nie jest czasem połączenia.
   Sprawdź postój/zły GPS/przerwę ponad 2 s oraz brak ponowienia po restarcie/menu.
   Przy zewnętrznym punkcie kontrolnym:
   jedna automatyczna próba Full Cleaning, postęp/wynik, powrót do wcześniejszego ekranu.
5. **Powrót:** jedź przez skonfigurowane skrzyżowanie i podejście: jedna próba bramy, bez sprzątania.
   Zaparkuj także w sąsiednim miejscu w dozwolonym obszarze.
6. **Drugi wyjazd tego dnia:** brama nadal działa; checkpoint nie wysyła drugiego automatycznego
   Full Cleaning. Nie trzeba dojeżdżać do dalszego skrzyżowania. Ręczny kafelek pozostaje niezależny.
7. **Zasilanie:** na postoju sprawdź rozruch dwuetapowy oraz bezpośredni, wybudzenie i powrót
   monitoringu bez akcji/odtworzenia checkpointu. Obserwuj dwa rzeczywiste wzrosty licznika
   wybudzeń i nową okazję parkingową w każdym cyklu. Pierwsza obserwacja licznika jest bazą;
   restart procesu/aktualizacja/przerwa GPS nie odblokowują użytej okazji. Pierwszy dobry GPS
   poza parkingiem wymaga rzeczywistego powrotu i postoju 5 s przed ponownym wyjazdem.
   Ponowne uruchomienie nie resetuje dziennej rezerwacji sprzątania.

Jeżeli robot nadal sprząta przy automatycznej próbie, aplikacja zgodnie z decyzją użytkownika
nie sprawdza stanu - wysyła rutynę. Zaplanuj kolejność prób, świadomie uwzględniając tę możliwość.
Nie dodawaj automatycznego stop/status tylko dla wygody testów. Dni/rollback, błędny JSON, timeout,
auth rejection i duplikaty są testowane syntetycznie; radio służy do realnej integracji.

## Odnowienie danych Roborock

Nie znamy stałego TTL. Po odrzuceniu użyj komputerowego helpera, który wyłącznie loguje
się kodem email i odczytuje listę rutyn. Wymaga dokładnie jednej rutyny „Full Cleaning”.

```sh
python3.13 -m venv "$PRIVATE_DIR/roborock/.venv"
"$PRIVATE_DIR/roborock/.venv/bin/pip" install 'python-roborock==7.4.1'
"$PRIVATE_DIR/roborock/.venv/bin/python" scripts/bootstrap-roborock.py \
  "$PRIVATE_DIR/roborock/routine-credentials-new.json"
```

Nie twórz ponownie istniejącego venv bez potrzeby. Email i kod są wprowadzane interaktywnie,
nie w argumentach. Helper zapisuje tylko minimalny pakiet z uprawnieniami właściciela i odmawia
nadpisania starego pliku. Nie wyświetla danych konta, nie wykonuje rutyny. Przy wielu rutynach
o tej nazwie nie zgaduje urządzenia. Zweryfikuj wybrany pakiet przez dry-run i dostarcz
instalatorem lub wklej w ustawieniach. Zapis nie ponawia wcześniej nieudanej próby.
Nowy helper ma test ekstrakcji i walidacji; pełnego nowego logowania nie uruchamiamy bez potrzeby.

### Uzupełnienie Full Mop

Od `0.3.0-local` helper szuka także dokładnej nazwy `Full Mop` na tym samym urządzeniu,
na którym znalazł jedyną rutynę `Full Cleaning`. Więcej niż jeden pasujący Mop jest błędem,
nie wyborem pierwszej pozycji. Brak Mop zachowuje zgodny starszy pakiet tylko do Cleaning.
Identyfikator zapisuje jako `full_mop_routine_id` w prywatnym JSON. Nie wpisuj ID do kodu,
testów ani dokumentacji. Pełny import zapisuje oba ID zaszyfrowane i nie uruchamia rutyn.
Próba sprzętowa Mop wymaga świadomego ręcznego wybrania kafelka, jednego polecenia, wyniku
i powrotu do menu po pięciu sekundach. Nie zatrzymuj robota po teście i nie resetuj limitu dziennego.

## Diagnostyka i odzyskiwanie

- Brak automatyzacji: sprawdź maintenance/staging, włączenie, GPS, uprawnienia i autostart.
  Brak numeru/pakietu blokuje tylko odpowiadającą akcję; zużyty limit dzienny nie jest błędem GPS.
- Brak ekranu z tła: sprawdź uprawnienie overlay oraz systemowy log blokady BAL. Nie obchodź
  ograniczeń Accessibility ani klikaniem innych aplikacji. Wstrzymaj odbiór wersji jako sprawdzonej.
- Roborock auth: formularz wymiany; nie loguj treści odpowiedzi/nagłówków. Błąd czasu wymaga
  sprawdzenia zegara. Brak Internetu lub timeout nie tworzy kolejki. Ręcznie sprawdź telefon przed retry.
- Przerwany import: pozostaw maintenance, popraw prywatny plik i ponownie uruchom instalator.
  Nie usuwaj blokady na ślepo. Jeżeli staging już zniknął, ale maintenance pozostał, ponowny
  kompletny import jest bezpieczniejszy od ręcznego odblokowania niesprawdzonych danych.
- Nieudana normalna aktualizacja zachowuje blokadę. Ponów bieżący instalator z poprawnym APK,
  mapą i pełną konfiguracją. Nie ma automatycznego rollbacku, kopii APK/danych/map ani `install -d`.
  Nie odinstalowuj aplikacji i nie czyść danych: utracisz konfigurację, stan bezpieczeństwa i Keystore.

Logi `DuduHome` zawierają nazwy zdarzeń, pominięcia i kategorie wyniku. `DuduGate` opisuje
bezpieczną sesję Binder. Pełne logi firmware mogą zawierać numer, więc są zawsze prywatne.
Przed commitem/publicznym issue użyj skanu z obiema prywatnymi listami oraz przeglądu ręcznego:

```sh
python3 scripts/check-public-tree.py --working-tree --all-history \
  --private-config "$PRIVATE_DIR/config.json" \
  --private-roborock "$PRIVATE_DIR/roborock/routine-credentials.json"
git diff --cached --check
```

Właściciel zatwierdził publikację kodu rozwojowego po audycie prywatności i bieżącej
weryfikacji na radiu. Nie oznaczaj go jako finalnej wersji przed ukończeniem wszystkich
prób przejazdów/rozruchu. Publiczny baseline pozostaje punktem powrotu; dokumentuj
osobno wykonane testy i brakujące próby sprzętowe. Nie publikuj APK ani danych prywatnych.

## Trzy cele nawigacji

Format i import z systemowego selektora plików: [NAVIGATION.md](NAVIGATION.md).
Opcja instalatora `--navigation` jest opcjonalna: brak zachowuje istniejący plik na radiu,
puste sloty czyszczą tylko cele nawigacji. Prywatny plik musi mieć uprawnienia 0600 i znajdować
się poza Git. Instalator odrzuca starszy versionCode oraz inny podpis APK; nie czyści danych.
Po aktualizacji otwórz menu, potwierdź usunięcie staging/maintenance, wersję i wszystkie sloty.
Sprawdź każdy cel przez kliknięcie w aplikacji, aktywny ekran prowadzenia Maps i powrót do menu.
Nie umieszczaj tych zrzutów ani intentów z koordynatami w PR. Brak połączenia ADB nie jest
udanym wdrożeniem; zapisuj status jako oczekujący aż do odczytu wersji z fizycznego urządzenia.

## Grupy lokalizacji od 1.0.0-rc4/code 17

Agent konfiguruje miejsca zgodnie z pełną instrukcją [NAVIGATION.md](NAVIGATION.md).
Plik schema 2 ma listę destinations oraz etykietę/ikonę grupy na slot. Inne sloty zachowaj
w pełnym prywatnym pliku: import zastępuje całość. Najpierw aktualizuj APK do code 17 lub
nowszego, potem importuj schema 2. Nie importuj publicznych przykładów do radia właściciela.
Walidacja instalatora obejmuje obie wersje; stary APK z nowym formatem jest odrzucany.
Nie obniżaj wersji APK, aby obejść niezgodny format; ponów import w obsługiwanej wersji.

Po zatwierdzeniu automatycznej instalacji wykonaj ją po uzyskaniu zweryfikowanego ADB bez
pytań o jazdę/postój: stan bez aktywnej rozmowy/akcji, walidacja i sumy, zgodny podpis, aktualizacja
bez kasowania danych, pełny import, kontrola staging/maintenance/limitów, test kafelków i modalu,
powrót do menu. Gdy trwa akcja, poczekaj na cleanup. Gdy brak ADB, zachowaj artefakty do wznowienia;
nie raportuj instalacji jako wykonanej. Do testu tej funkcji nie potrzeba dzwonienia ani robota.

Przed publikacją dodaj `--private-navigation "$PRIVATE_DIR/navigation.json"` do pełnego skanu.
Skan musi objąć metadane grupy i wszystkie cele, nie tylko pierwszy. Wszystkie publiczne
opisy, instrukcje dla agentów i testy używają syntetycznych etykiet i współrzędnych blisko zera.
