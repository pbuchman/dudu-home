# Roborock: architektura, protokół i autoryzacja

## Granice odpowiedzialności

| Element | Odpowiedzialność |
|---|---|
| `HomeDetector` | Czysta logika GPS w lokalnych metrach; zwraca zdarzenia, nie wykonuje akcji |
| `HomeMonitorService` | Pomiar GPS, odzyskiwanie subskrypcji, trwały zapis zużytych zdarzeń przed dispatch |
| `HomeActions` / `HomeAction` | Przypisanie zdarzenie → akcja, wspólna blokada, krótkotrwały wewnętrzny token UI |
| `DailyCleaning` | Synchroniczna rezerwacja pierwszej automatycznej próby dnia |
| `MainActivity` | Menu, formularze, pochodzenie akcji, postęp i prezentacja wyniku |
| `GateCallCoordinator` | Niezmieniony bezpieczny wykonawca raw Binder/SYU |
| `RoborockCredentials` | Walidacja i kanonizacja minimalnego pakietu |
| `RoborockStore` | AES-GCM z kluczem Android Keystore i trwałe oznaczenie odrzucenia |
| `RoborockClient` | Jeden podpisany POST HTTPS; żadnych poleceń kontroli robota poza wykonaniem rutyny |
| `PrivateImport` | Jednorazowe pobranie stagingu i oddzielenie danych lokalizacji od sekretów |

Brak serwera pośredniego, Home Assistant, Google Home, Pythona na Androidzie, MQTT i bibliotek
Android firm trzecich. Opcjonalny Python na komputerze służy tylko do logowania i odczytania
identyfikatora istniejącej rutyny. Nie jest wymagany podczas działania radia.

## Minimalny pakiet — poufny, poza Git

Pakiet JSON ma `schema_version: 1`, `api_base_url`, dodatni całkowity `routine_id`,
`routine_name: "Full Cleaning"` i obiekt `auth` z polami `u`, `s`, `h` z RRiot.
Nie potrzebujemy tokenu logowania konta, RRiot `k`, hasła, kodu email, identyfikatora urządzenia,
map pomieszczeń ani adresu MQTT. Cały pakiet jest sekretem, nawet jeśli UI skrótowo nazywa go danymi dostępowymi.

Nie ma potwierdzonego przez producenta stałego TTL tych danych ani zaimplementowanego
`refresh_token`. Nie należy obiecywać, że będą działały określoną liczbę dni. Kod email jest
jednorazowym etapem logowania, nie poświadczeniem do codziennych żądań. Czas jego ważności
należy odczytać z konkretnego emaila; nie jest terminem ważności zapisanej autoryzacji RRiot.

Odrzucenie przez HTTP 401 lub `auth.err.invalid.token` w `msg`/`code` oznacza trwałe zablokowanie
aktualnie użytego pakietu i formularz wymiany. Inny błąd 403, 429, 5xx czy nieznany JSON nie jest
sam w sobie dowodem wygaśnięcia danych. Nie wykonujemy automatycznego logowania, ponawiania ani
testu autoryzacji przy zapisie. Pakiet zastąpiony przez użytkownika nie może zostać oznaczony
jako odrzucony przez spóźnioną odpowiedź dotyczącą poprzedniego pakietu.

## HTTPS i podpis Hawk

Wzorzec pochodzi z `python-roborock` **7.4.1**, `RoborockApiClient.execute_scene` oraz
`_get_hawk_authentication` w `roborock/web_api.py`. To nieudokumentowane publicznie API
aplikacji producenta, nie kontrakt z gwarancją kompatybilności.

```text
POST /user/scene/{routine_id}/execute
body: empty
Authorization: Hawk id="u",s="s",ts="epoch_seconds",nonce="random",mac="signature"

preimage = u + ':' + s + ':' + nonce + ':' + epoch_seconds + ':' + lowerhex(MD5(path)) + '::'
signature = Base64(HMAC-SHA256(key = h, UTF-8(preimage)))
```

Losowy nonce: 6 bajtów SecureRandom, URL-safe Base64 bez paddingu. Czas: sekundy Unix;
radio musi mieć poprawny zegar. JSON `success` musi być rzeczywistą wartością boolean `true`
przy odpowiedzi 2xx. Samo HTTP 200 nie wystarcza. Nie zapisujemy odpowiedzi serwera w logach.

Dozwolone adresy to wyłącznie `https://api-eu.roborock.com`, `https://api-us.roborock.com`,
`https://api-cn.roborock.com`, `https://api-ru.roborock.com`, bez niestandardowego portu,
userinfo, query, fragmentu i dodatkowej ścieżki. Nie podmieniamy regionu po błędzie.
TLS używa systemowej walidacji certyfikatów, cleartext jest wyłączony, przekierowania są wyłączone.
Identyfikator jest dodatnią liczbą całkowitą, a pola podpisu nie dopuszczają wstrzyknięcia nagłówków.

`HttpsURLConnection`, POST o stałej długości 0 i brak pętli retry. Connect timeout 5 s,
read timeout 10 s, limit odpowiedzi 16 KiB. UI ma granicę oczekiwania 20 s i rozłącza transport,
ale blokada operacji pozostaje do wyjścia wątku sieciowego. DNS/systemowy stos mogą opóźnić
cleanup; nie uruchamiamy równoległego żądania, aby to ukryć.

Brak odpowiedzi jest wynikiem **nieznanym**: serwer mógł przyjąć żądanie. Zamknięcie połączenia
HTTP nie cofa zlecenia, nie zatrzymuje robota i nie wywołuje `stop`. Ta integracja nie gwarantuje
idempotencji po stronie chmury. Ponowienie ręczne jest nową świadomą próbą.

## Stan trwały i bezpieczeństwo

- `daily_cleaning.last_attempt_day`: `LocalDate` Europe/Warsaw jako epoch-day, zapis `commit()`
  przed dispatch. Wszystkie daty mniejsze/równe zapisanej są blokowane dla automatyzacji.
- `gate_settings`: numer, konfiguracja cooldown i rezerwacja dial. Wykonawca zachowuje osobną
  blokadę procesu i kontrolę przy samym dial, nie tylko w UI.
- `home_detector`: flagi zużytych zdarzeń i hash samych punktów. Zmiana autoryzacji nie resetuje trasy.
- `no_backup/roborock.enc`: IV i szyfrogram AES-GCM; klucz w Android Keystore, poza plikiem.
- `roborock_state.rejected`: flaga, bez wartości poświadczeń.
- `no_backup/home-config.json`: GPS i włączenie monitoringu, bez pakietu Roborock i numeru po imporcie v2.
- `pending-config.json` / `maintenance`: jawny staging dostępny tylko w prywatnych danych aplikacji;
  do końca importu automat nie działa. Staging znika po sukcesie, instalator go nie loguje.

Walidacja całego importu następuje przed zapisem sekcji. Zapis wieloplikowy nie jest transakcją
bazodanową: przy przerwaniu pozostaje maintenance i importer można uruchomić ponownie. Porównanie
z zapisanymi danymi zapobiega ponownemu zapisowi identycznego numeru i niepotrzebnemu cooldown.
Nie usuwamy dziennego limitu ani stanu trasy podczas aktualizacji. Brak sekcji opcjonalnej
pozostawia dotychczasowy numer/pakiet; brak punktów przy wyłączonym automacie jest dopuszczalny.

AES nie chroni przed uprzywilejowanym, odblokowanym ADB ani złośliwym systemem radia. Debug APK
zachowujemy dla zgodności z dotychczasową instalacją; nie jest to deklaracja zabezpieczenia
produkcyjnego przed właścicielem/rootem urządzenia. Android backup i transfer są wyłączone.

## Start i tło

BOOT_COMPLETED i MY_PACKAGE_REPLACED mogą uruchomić usługę, jeśli konfiguracja i uprawnienia
na to pozwalają. DUDU wake task powinien wskazywać `.HomeWakeActivity` — bez ekranu i bez akcji.
`MainActivity` pozostaje wejściem menu. Watchdog GPS ma okres 15 s. Runtime evidence po
przerwie jest czyszczone; trwale zużyte zdarzenia nie są odtwarzane jako nowe żądania.

Zezwolenie overlay służy do legalnego pokazania zwykłej Activity z tła, nie do imitowania dialera.
Ograniczenia Androida i producenta mogą nadal zablokować start/wybudzenie; **wymagany jest test
fizycznego DUDU7**, nie tylko emulator. Nie ma Accessibility ani powiadomień alarmowych.

## Źródła i aktualizacja kontraktu

- [python-roborock 7.4.1](https://pypi.org/project/python-roborock/7.4.1/)
- [Źródło biblioteki — web_api.py](https://github.com/Python-roborock/python-roborock/blob/main/roborock/web_api.py)
- [Android: start Activity z tła](https://developer.android.com/guide/components/activities/background-starts)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

Link do gałęzi main może się zmieniać; punktem odniesienia implementacji jest wersja 7.4.1
sprawdzona lokalnie. Test podpisu zawiera syntetyczny wektor porównany niezależnie z Pythonem.
Nie zmieniaj podpisu lub zachowania po błędzie metodą prób automatycznie odpalających robota.
