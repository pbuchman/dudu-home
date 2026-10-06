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
- `GET /v1/latest`, `/v1/day`, `/v1/range`, `/v1/live` według CONTRACT.md.

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
fixtures przekazanych ścieżką. Dowody/screenshoty są w `output/`, wyniki odbioru w `../../docs/VERIFICATION.md`.
Testy API/SSE/mapy korzystają wyłącznie z syntetycznych odpowiedzi. Logo/style w testach są
mockiem, nie odbiorem MapTiler. Live backend, host prywatny i radio wymagają integratora.
