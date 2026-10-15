# Routebook UI

Prywatny POC React/TypeScript/Vite/MapLibre. Metry, postoje i podział na runy pochodzą
z backendu. UI zmienia wyłącznie prezentację: km, format czasu Europe/Warsaw i próbkowanie
geometrii zakresu. Nie używa ingest tokenu. Nie uruchamia kolektora ani instalacji na radiu.

## Instalacja i lokalne demo

Instaluj z katalogu nadrzędnego `routebook`, używając wspólnego lockfile.

```sh
cd ..
npm ci
cd ui
ROUTEBOOK_DEMO=1 ROUTEBOOK_FIXTURES_PATH=../contract/fixtures.json npm run dev --workspaces=false
```

Serwer deweloperski słucha wyłącznie `127.0.0.1:5178`. Demo czyta wskazany plik kontraktu,
nie zawiera jego własnej kopii. W demo panel scenariuszy pozwala sprawdzić lukę GPS,
postój dokładnie 180 s, północ, późne dane, błędną paczkę i zegar przyszły. Każdy przypadek
ma nowy adapter i niezależne rewizje. Przyciski czasu/SSE/offline są jawnie demonstracyjne.

## Runtime prywatnego UI

Budowanie nie potrzebuje żadnego klucza:

```sh
npm run build --workspaces=false
```

Wynik: `dist/`. Prywatny serwer powinien dostarczać na tym samym origin:

- statyczne `dist/` oraz skompilowany worker MapLibre z `dist/assets/`;
- `GET /routebook-config.json`, `Cache-Control: no-store`, z prywatnego pliku poza repo;
- `GET /v1/latest`, `/v1/day`, `/v1/range`, `/v1/live` według CONTRACT.md;
- odczyty `/v1/analytics`, `/v1/analytics/journey` i prywatne CRUD `/v1/places`
  według [kontraktu analiz](../contract/ANALYTICS.md).

Konfiguracja JSON ma tylko pola `mode` (`api`), `deviceId` (provisioned UUID), opcjonalnie
`mapTilerKey` (klucz mapowy do przeglądarki) i `mapTilerStyle` (domyślnie `streets-v4`).
Nie dodawać ingest tokenu, danych uwierzytelniających serwer lub innych sekretów do JSON.
Klucz MapTiler jest z natury widoczny przeglądarce, odrębny od machine Bearer; przy wdrożeniu
ograniczyć go do prywatnego origin. Bez `mapTilerKey` UI działa z podsumowaniami i jawnym
stanem „Mapa niedostępna”. Błąd dostawcy także pozostawia podsumowania. Brak deviceId
pokazuje ekran konfiguracji zamiast pozornych pustych danych. Nie ma publicznego UI.

Lokalny adapter API można uruchomić z `ROUTEBOOK_RUNTIME_CONFIG=/absolute/private/config.json`.
Vite dostarcza ten plik tylko w trybie dev; API należy wtedy wystawić przez ten sam origin
(np. prywatny reverse proxy). Vite nie proxyuje API do wymyślonego adresu backendu.
Po produkcyjnym build runtime JSON nadal jest obowiązkiem prywatnego serwera, a nie plikiem
kopiowanym przez build do repo. Dla zwykłego prywatnego deploymentu nie włączać demo routes.

MapTiler Free: UI zachowuje tekstową atrybucję i logo linkujące do dostawcy. Nie zakłada
stałych limitów planu. Style/tiles i logo idą do MapTiler, GeoJSON trasy pozostaje lokalnym
źródłem MapLibre. Źródła:
[MapLibre Vite/worker](https://maplibre.org/maplibre-gl-js/docs/),
[MapTiler API](https://docs.maptiler.com/cloud/api/reference/),
[atrybucja MapTiler](https://docs.maptiler.com/guides/map-design/attribution/add-attribution/).

## Odczyt i ograniczenia geometrii

SSE snapshot oraz reconnect ponawiają wybrany odczyt. Aktualizacja odświeża wybór, jeśli
affected_dates go obejmują lub rewizja przeskoczyła. Niższe rewizje są odrzucane. Świeżość
używa server_time i monotonicznego czasu przeglądarki; po 30 s bez nowych zdarzeń pozycja
staje się „Ostatnio widziany”. future_clock nie dostaje znacznika live przed czasem pomiaru.
Wybrana data nie przeskakuje na dzisiejszą po SSE. Klient EventSource reconnectuje natywnie;
Last-Event-ID nie jest traktowany jako gwarancja replay.

Mapa zakresu pobiera dni kolejno, w porcjach po siedem, dwoma workerami. Anuluje żądania
poprzedniego wyboru i odrzuca ich wyniki. Anulowanie samej mapy nie anuluje /range z
podsumowaniem. Aktualizacje podczas ładowania zakresu są łączone w odświeżenie po zakończeniu,
żeby regularne GPS nie restartowało pobierania bez końca; istniejąca mapa pozostaje widoczna
podczas odświeżania. Postęp pokazuje częściowe pobranie, anulowanie i daty błędów.

Do 1000 punktów na dzień zakresu; wszystkie końce runów, edge_from i postoje zachowane
nawet ponad budżet. Dzień ma pełną geometrię. Nie ma linii między runami, edge_from to
odrębna krawędź. Luka GPS ma oba czasy pomiarów i nie jest postojem. Podczas częściowego
ładowania UI nie wnioskuje luki przez dni, których jeszcze nie pobrano. Pełny czas postoju
przez północ nie jest sumowany ponownie: UI pokazuje także overlap_seconds danego dnia.

## Weryfikacja

```sh
ROUTEBOOK_FIXTURES_PATH=../contract/fixtures.json npm test --workspaces=false
ROUTEBOOK_DEMO=1 ROUTEBOOK_FIXTURES_PATH=../contract/fixtures.json npm run test:browser --workspaces=false
```

Przy pierwszym lokalnym odbiorze: `npx --workspaces=false playwright install chromium`.
Browser plugin nie był dostępny; QA używa Playwright Chromium. Testy wymagają oryginalnych
fixtures przekazanych ścieżką. Dowody/screenshoty i raport JSON są domyślnie w `/tmp/dudu-routebook-14-ui/`
(lub katalogu `ROUTEBOOK_UI_EVIDENCE_DIR` poza repo); wyniki odbioru w `../../docs/VERIFICATION.md`.
Testy API/SSE/mapy korzystają wyłącznie z syntetycznych odpowiedzi. Logo/style w testach są
mockiem, nie odbiorem MapTiler. Live backend, host prywatny i radio wymagają integratora.


## Dziennik i analizy 1.4

Zakładka **Analizy** pobiera metryki bezpośrednio z pełnych obserwacji backendu;
nie wylicza ich z uproszczonej mapy zakresu. Dzień, tydzień od poniedziałku i miesiąc
wyznaczają daty w Europe/Warsaw. Brak obserwacji pokazuje brak danych. Czas/dystans
podsumowania obejmują przejazdy, bez parkingu ≥10 min między nimi; obserwowane wizyty
mają osobne pełne czasy i część przypadającą na okres. Podróż przecinająca granicę
okresu ma pełne metryki w dzienniku, podsumowanie stosuje zakres kontraktu.

Wybrana podróż ma osobną mapę i wykres SVG z jednym kursorem. Dotyk/kliknięcie mapy
wybiera najbliższy zapisany pomiar, wykres i suwak wybierają pomiar czasowy; strzałki,
Home i End działają na wykresie. Odtwarzanie 1–120× nigdy nie interpoluje brakującej
trasy: skacze między rozłączonymi runami i pokazuje komunikat o pominięciu. Fioletowy
znacznik jest historyczny, nie live. Brak/odrzucona prędkość przerywa linię; maksimum
i średnie pochodzą z backendowego filtra jakości, a średnia całej podróży jest pusta
przy niekompletnym zapisie.

Każda nowa rewizja SSE odświeża analizy, nawet bez daty w affected_dates. Zmiana
okresu/podróży anuluje poprzednie żądanie. Refresh usuwa poprzedni replay; lista i
szczegóły wymagają identycznej pary rewizji danych/metadanych i wersji algorytmu.
Wyścig rewizji ma dwa automatyczne ponowienia, potem jawny komunikat z odświeżeniem.

Nazwane miejsca zapisuje backend z optymistyczną rewizją i nagłówkiem
`X-Routebook-Write: 1`, bez tokenu ingest w przeglądarce. Formularz przyjmuje nazwę,
środek i promień 25–1000 m; można go otworzyć z wizyty lub kursora. React renderuje
nazwy jako tekst. Usuwanie wymaga potwierdzenia w wierszu i usuwa tylko metadane.
Porównania zachowują kierunek A→B, pokazują medianę czasu/dystansu/postojów,
liczebność i liczbę kompletnych zapisów oraz podział według godziny wyjazdu.
Te same końce nie oznaczają identycznej geometrii. Demo bazowego kontraktu nie
udaje analiz; browser QA używa jawnych syntetycznych odpowiedzi nowego API.

Zajęty backend analiz (429) ma najwyżej dwa automatyczne ponowienia odczytu,
po podanym Retry-After (1–5 s). Oczekiwanie nie blokuje UI i reaguje na anulowanie.
Zapisy metadanych nigdy nie są ponawiane automatycznie; konflikt zachowuje szkic formularza.

Odczyt analiz obejmuje najwyżej 31 dni i 650000 punktów z ograniczonym kontekstem;
szczegóły jednej podróży mają limit 100000 punktów. Przekroczenie limitu daje jawny
błąd, bez obcinania danych. Statystyki oblicza backend; ten UI nie zmienia Androida,
kolektora GPS ani danych źródłowych.
