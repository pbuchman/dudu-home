# Dudu Home - plan wdrożenia i odbioru

## Aktualizacja 0.5.6

Bieżąca kolejka i start Spotify są opisane w [AUTOMATION_SEQUENCE.md](AUTOMATION_SEQUENCE.md).
Zastępuje to wcześniejszy brak kolejki i powrót do pulpitu po Yanosiku. Odbiór na radiu
pozostaje otwarty; poniższe sekcje są historycznym planem wcześniejszych wersji.

## Aktualizacja 0.5: pasek automatyzacji

[PROGRESS_UI.md](PROGRESS_UI.md) opisuje kontrakt i implementację w osobnej gałęzi
`codex/automation-progress` od `origin/main` (`911d18b`). Zachowano adapter wybudzenia,
diagnostykę i powrót od wschodu. Dodano obserwację, jeden pasek, wyniki i testy. Publikacja
obejmuje kod i bezpieczne zrzuty, nie APK. Test na postoju i prezentacja automatyczna na radiu
pozostają do wykonania po integracji zadania zapłonu w oddzielnym zadaniu. Poniżej historia
poprzednich etapów i nadal obowiązujące zabezpieczenia.

Decyzje funkcjonalne są zatwierdzone. Nie wymagają ponownego pytania o menu, ręczne akcje,
jedną automatyczną próbę dziennie, brak sprawdzania stanu robota lub brak automatycznego stop.
Brak urządzenia, niezgodny numer/podpis albo zagrożenie dla danych należy jawnie zgłosić.

## 1. Zachować działający punkt wyjścia - wykonane

- Prywatny poprzednik, APK, podpis i pełne dane kalibracji poza nowym repozytorium; oryginałów nie usuwać.
- Nowa, czysta historia `pbuchman/dudu-home`, MIT, source only, `v0.1.0-baseline`.
- Lokalne zmiany menu/detektora/cooldown zabezpieczone osobnym commitem przed Roborock.
- Rozwój na `codex/home-automation`; pierwotna wersja zachowana pod tagiem `v0.1.0-baseline`.
  Na późniejsze polecenie właściciela aktualny kod udostępniamy na main jako wersję rozwojową.

## 2. Natywne wykonanie Full Cleaning - zaimplementowane, lokalnie testowane

- `RoborockCredentials`: ścisła walidacja endpointu, rutyny i pól Hawk.
- `RoborockStore`: szyfrowany zapis Keystore, brak backupu, trwała blokada odrzuconego pakietu.
- `RoborockClient`: dokładnie jeden POST wykonania istniejącej rutyny, podpis zgodny z biblioteką,
  timeouty, limit odpowiedzi, brak redirect/retry/status/stop.
- Błędy: oddzielić auth rejection, niepewny wynik sieci, odmowę serwera i uszkodzony protokół.
  Nie pokazywać „sprzątanie zakończone” po samym przyjęciu żądania.
- Komputerowy bootstrap służy jedynie odnowieniu minimalnego pakietu; nie uruchamia robota.

## 3. UI i automatyzacja - zaimplementowane, lokalnie testowane

- Dwa duże kafelki, niewielkie ustawienia, osobne formularze numeru i pakietu.
- Ręczna akcja → postęp → wynik → menu. Automatyczna → ten sam przebieg → ukrycie, chyba że menu było otwarte.
- Zachować czasy prezentacji, trwałe błędy, bezpieczne wyjście i jawne ręczne retry.
- `HomeActions`: wspólna blokada bramy i HTTP; bez równoległych prób ani kolejki.
- `DailyCleaning`: raz na dzień Europe/Warsaw, rezerwacja przed próbą, brak resetu po błędzie,
  zapisie, restarcie i cofnięciu zegara. Ręczne uruchomienia poza tym limitem.
- Podpiąć Full Cleaning tylko do `OUTBOUND_CHECKPOINT`. Brama pozostaje przy rozpoczęciu
  wyjazdu i dojeździe powrotnym. Nie zmieniać kalibracji bez brakującego dowodu.

## 4. Pełny prywatny deployment - wykonany na radiu

- Weryfikacja formatu poza radiem; numer potwierdzony porównaniem na radiu, nie historycznym opisem.
- Sprawdzenie pakietu i zgodności certyfikatu, prywatny backup APK/danych z sumami, `install -r`.
- Maintenance podczas aktualizacji, jednorazowy staging przez stdin, komplet numer/GPS/Roborock.
- Grant uprawnień, brak akcji instalatora, kontrolowane pierwsze otwarcie na postoju.
- Zachowanie numeru, daily quota, stanu detektora i podpisu; brak sekcji nie resetuje istniejących sekretów.
- Dokumentacja rollbacku, odnowienia poświadczeń i ograniczeń kopii Keystore.

## 5. Weryfikacja lokalna i pamięć projektu

Build/lint, testy Android na emulatorze, test wektora Hawk i atrap transportu wyłącznie w testach,
AES-GCM/rejection/replacement, formularze bez akcji, UI manual/auto, trwałe blokady, import i update
na emulatorze. Testy detektora i ponowny replay ośmiu prywatnych przejazdów. Prywatny dry-run.
Przegląd UI oraz całego staged tree i historii z listami rzeczywistych danych prywatnych.
Commity logiczne: wykonawca/UI; narzędzia instalacji i konfiguracji; dokumentacja/testy i faktyczne wyniki.

Każdy wynik zapisujemy w [VERIFICATION.md](VERIFICATION.md), zmiany w [CHANGELOG.md](../CHANGELOG.md),
punkt kontynuacji w [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md). Szczegóły prywatne pozostają poza Git.

## 6. Odbiór na radiu - częściowo wykonany

Instalacja pełnego pakietu, ręczna brama (SUCCESS), ręczny Full Cleaning (ACCEPTED), powrót
do menu i GPS w tle są potwierdzone. Szczegóły w [VERIFICATION.md](VERIFICATION.md).
Nie powtarzać tych akcji tylko dla ponownego zaliczenia; poniżej zakres pełnego odbioru.

Wykonać [sesję operacyjną](OPERATIONS.md#minimalna-sesja-akceptacyjna-na-radiu): instalacja pełnego
pakietu, brama i Full Cleaning z ręcznych kafelków na radiu, start/wybudzenie bez akcji, wyjazd
z obiema automatyzacjami, powrót z samą bramą, drugi checkpoint tego dnia bez kolejnego HTTP,
restart z zachowaniem blokad i ochrona istniejącej rozmowy. Bez automatycznego zatrzymywania robota.

Nie powtarzać kampanii ośmiu tras bez konkretnej luki. Kolejny dzień, clock rollback i auth failure
mogą pozostać testami syntetycznymi; nie manipulować datą ani prawdziwym kontem dla pozornego zaliczenia.

## Kryterium ukończenia

Cały komplet konfiguracji zainstalowany, realne akcje wykonane z radia, brama bez naruszenia rozmów,
odkurzacz realizuje właściwą rutynę, automatyzacja nie duplikuje prób, błędy i UI zgodne z kontraktem,
boot/wake potwierdzone. Dopiero wtedy oznaczyć pełny odbiór i finalny tag.
Właściciel osobno zatwierdził wcześniejszą publikację kodu rozwojowego po audycie prywatności
i dokumentacji; nie oznacza to zaliczenia brakujących prób. Jeśli radio/kierowca są niedostępni, zakończyć prace lokalne,
wskazać brakujące próby i **nie oznaczać pełnej funkcjonalności jako sprawdzonej produkcyjnie**.
