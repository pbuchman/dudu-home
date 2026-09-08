# Instalacja, konfiguracja i odzyskiwanie

## Zasady dla operatora i kolejnego agenta

Pracuj w tym repozytorium, nie w prywatnym poprzedniku. Nie potrzebujesz starej sesji czatu:
kontrakt jest w [FUNCTIONAL.md](FUNCTIONAL.md), technika w [ROBOROCK.md](ROBOROCK.md),
aktualne dowody w [VERIFICATION.md](VERIFICATION.md). Nigdy nie zakładaj, że historyczny numer,
adres ADB lub pakiet poświadczeń nadal są poprawne. Nie wyświetlaj sekretów w terminalu/chacie.

Aktualizacja radia wymaga: samochód bezpiecznie zaparkowany, żadnej trwającej rozmowy ani akcji,
autoryzowane ADB oraz przygotowane prywatne pliki. Nie odinstalowuj aplikacji i nie czyść danych.
Testy fizycznych akcji wykonujemy dopiero w aplikacji zainstalowanej na radiu — nie z komputera.

## Prywatny katalog

`PRIVATE_DIR` wskazuje katalog poza wszystkimi repozytoriami (0700); pliki mają 0600.
W instalacji właściciela jest to sąsiedni katalog `dudu-home-private`. Jego
`CURRENT_IMPLEMENTATION.md` wskazuje lokalne archiwum, konfigurację i ostatni stan bez potrzeby
odtwarzania czatu. Nie kopiuj zawartości do Git ani publicznych issue.

```text
PRIVATE_DIR/
  config.json                       # prywatny numer, GPS, źródła i instalacyjne metadane
  roborock/routine-credentials.json  # minimalny pakiet do wykonania Full Cleaning
  radio-backups/                    # APK, dane i sumy przed kolejnymi aktualizacjami
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
| `points.gate` | Brama, cel początkowego ruchu wyjazdowego |
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

4. Gdy samochód stoi i nic nie wykonuje akcji, wykonaj pełną aktualizację:

   ```sh
   python3 scripts/configure-device.py DEVICE_SERIAL "$PRIVATE_DIR/config.json" \
     --roborock "$PRIVATE_DIR/roborock/routine-credentials.json" \
     --apk app/build/outputs/apk/debug/app-debug.apk \
     --backup-dir "$PRIVATE_DIR/radio-backups" --enable-automation
   ```

   Instalator wymaga istniejącej aplikacji, porównuje numer z `gate_settings`, sprawdza
   pakiet/versionCode APK i oba certyfikaty przez `apksigner`. Robi prywatną kopię zainstalowanego
   APK i danych oraz manifest SHA-256, weryfikuje odczyt. Przed snapshotem danych zatrzymuje aplikację.
   Następnie zakłada maintenance, wyłącza starą konfigurację automatu i wykonuje `adb install -r`.
   Nie zmienia klucza, nie wykonuje uninstall, nie przywraca starego numeru na ślepo.

5. Konfiguracja jest atomowo przenoszona do `no_backup/pending-config.json`; suma odczytanego
   pliku musi się zgadzać. Nadawane są fine/coarse location, background location od API 29,
   notifications od API 33 oraz overlay. Przy błędzie grant/import automat pozostaje wstrzymany.
6. Otwórz Dudu Home **na postoju**. Import waliduje sekcje, zapisuje je prywatnie, szyfruje
   Roborock i usuwa staging/maintenance. Nie wysyła telefonu ani HTTP. Sprawdź menu i stan
   automatyzacji. Systemowy GPS i uprawnienia muszą rzeczywiście działać; sam sukces `pm grant`
   nie potwierdza dostępu do świeżych pomiarów.
7. Zweryfikuj obecność wszystkich sekcji odczytem logicznym/rozmiarami, nie wypisaniem wartości.
   `roborock.enc` powinien istnieć, staging i maintenance zniknąć. `home-config.json` po imporcie v2
   nie zawiera numeru ani pakietu Roborock. Numer porównano już przed instalacją.
8. Ustawienie autostartu/wybudzenia DUDU ma wskazywać `.HomeWakeActivity`. Zweryfikuj faktyczny
   dostęp do tej opcji na urządzeniu. BOOT_COMPLETED nie zastępuje vendorowego wybudzenia.
9. Wykonaj pakiet prób z następnej sekcji. Zachowuj logi wyłącznie w prywatnym katalogu.

Sama wymiana konfiguracji w aktualnym APK używa tej samej komendy bez `--apk` i `--backup-dir`.
Również zatrzymuje aplikację i czeka na ręczne otwarcie/import; wykonuj ją na postoju. Bez
`--enable-automation` staging celowo wyłącza monitoring. Nie zeruje blokady dziennej ani cooldown.

Świeża instalacja dla innego użytkownika: `scripts/install-on-device.sh DEVICE_SERIAL`, potem
formularz numeru na postoju. Helper odmawia aktualizacji już istniejącej aplikacji. Dla tego
radia używamy aktualizacji z kopią, nie ścieżki świeżej instalacji. Nie uruchamiaj baseline'u
automatycznie po instalacji — stary baseline po starcie z zapisanym numerem dzwoni.

Helper świeżej instalacji wymaga dokładnie jednego jawnego DEVICE_SERIAL. Przed buildem
odczytuje listę pakietów, również tych z zachowanymi danymi (`pm list packages -u`). Błąd ADB,
pusta lista lub nierozpoznany format przerywają pracę, zamiast oznaczać brak aplikacji.
Instalacja nie używa `-r`: pojawienie się pakietu po sprawdzeniu nie pozwala na jego cichą
aktualizację. Nie ma automatycznego ponowienia ani uruchomienia aplikacji. Regresję sprawdza
`python3 scripts/test-fresh-installer.py` na atrapach ADB/Gradle, bez operacji na urządzeniu.

## Minimalna sesja akceptacyjna na radiu

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
   Rusz w kierunku bramy: jedna automatyczna próba bramy. Przy zewnętrznym punkcie kontrolnym:
   jedna automatyczna próba Full Cleaning, postęp/wynik, powrót do wcześniejszego ekranu.
5. **Powrót:** jedź przez skonfigurowane skrzyżowanie i podejście: jedna próba bramy, bez sprzątania.
   Zaparkuj także w sąsiednim miejscu w dozwolonym obszarze.
6. **Drugi wyjazd tego dnia:** brama nadal działa; checkpoint nie wysyła drugiego automatycznego
   Full Cleaning. Nie trzeba dojeżdżać do dalszego skrzyżowania. Ręczny kafelek pozostaje niezależny.
7. **Zasilanie:** na postoju sprawdź rozruch dwuetapowy oraz bezpośredni, wybudzenie i powrót
   monitoringu bez akcji/odtworzenia checkpointu. Ponowne uruchomienie nie resetuje dziennej rezerwacji.

Jeżeli robot nadal sprząta przy automatycznej próbie, aplikacja zgodnie z decyzją użytkownika
nie sprawdza stanu — wysyła rutynę. Zaplanuj kolejność prób, świadomie uwzględniając tę możliwość.
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
- Przy nieudanej aktualizacji zachowaj katalog `before-update-*` i jego SHA-256. Przywrócenie
  danych obejmuje sekrety i stan automatyzacji; wymaga kontrolowanej sesji na postoju, nie `pm clear`.
- Rollback kodu: użyj poprzedniego APK z identycznym certyfikatem, ewentualnie `adb install -r -d`
  gdy Android dopuści downgrade debug. Najpierw wyłącz automat/maintenance. Nie odinstalowuj
  aplikacji: utracisz klucz Android Keystore i zaszyfrowany pakiet może stać się nieczytelny.
  Snapshot plików nie zawiera eksportu klucza Keystore. Po powrocie do wersji wspierającej Roborock
  można ponownie zaimportować z prywatnej kopii. Nie uruchamiaj samoczynnie starego baseline'u.

Logi `DuduHome` zawierają nazwy zdarzeń, pominięcia i kategorie wyniku. `DuduGate` opisuje
bezpieczną sesję Binder. Pełne logi firmware mogą zawierać numer, więc są zawsze prywatne.
Przed commitem/publicznym issue użyj skanu z obiema prywatnymi listami oraz przeglądu ręcznego:

```sh
python3 scripts/check-public-tree.py --working-tree --all-history \
  --private-config "$PRIVATE_DIR/config.json" \
  --private-roborock "$PRIVATE_DIR/roborock/routine-credentials.json"
git diff --cached --check
```

Nie publikuj automatyzacji ani tagu finalnej wersji przed ukończeniem realnych testów.
Publiczny baseline pozostaje punktem powrotu. Nowe commity lokalne są pamięcią pracy,
nie deklaracją odbioru hardware.
