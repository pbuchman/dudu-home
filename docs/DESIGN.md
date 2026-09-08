# Dudu Home — wygląd i materiały

## Kierunek

To panel trzech konkretnych akcji w samochodzie, nie dashboard smart home. Duże cele dotykowe,
krótkie podpisy, rozpoznawalne ilustracje i brak rozpraszających animacji w stanie spoczynku.
Dom z rozbłyskiem zastępuje dawny symbol listy: łączy wjazd do domu z jego sprzątaniem.

Paleta: tło `#101B27`, głęboki błękit `#172C3A`, tekst kafelków `#162934`, piaskowa brama
`#F0D5AE`, miętowe Cleaning `#C0E8D8`, błękitne Mop `#BDDAF4`. Kolor rozróżnia akcje, ale nie
zastępuje nazwy. Roborock Mop ma dodatkowo własną ilustrację z wyraźną kroplą.
Platformowy sans-serif / sans-serif-medium: nagłówek 32 sp, kafelki 26 sp.
Bez pobierania fontów, zależności UI i logotypów Google/Roborock.

Układ: nagłówek z ikoną po lewej, ustawienia po prawej; poniżej trzy równe cele dotykowe
o wysokości 280 dp. Ilustracja nad nazwą, razem wyśrodkowane w pionie. Na prośbę właściciela
usunięto podpis nagłówka i opisy powielające nazwy akcji. Status automatyzacji pod akcjami,
bez udawania potwierdzonej pozycji czy działania robota. Poniżej 600 dp szerokości kafelki
układają się pionowo w przewijanej zawartości. Ekrany postępu/sukcesu używają ilustracji akcji.

Przegląd projektu: odrzucono miniaturową kroplę nakładaną na ten sam bitmapowy robot — była
nieczytelna po skalowaniu. Finalny Mop ma osobną wysokiej rozdzielczości ilustrację.
Nie dodano ozdobnych liczników, wykresów, dekoracyjnych etykiet ani kolejnych ekranów.

## Dostępność i zachowanie

- Cały kafelek jest przyciskiem z jedną etykietą dostępności; ilustracje nie są osobnymi celami.
- Widoczny fokus klawiatury i reakcja ripple; tekst o wysokim kontraście na pastelowych kafelkach.
- Treść jest tekstem Androida, nie wypalonym napisem w grafice. Powiększenie tekstu pozostaje możliwe.
- Sukces pozostaje przez 5000 ms; animacja nie opóźnia wysłania polecenia. Błąd wymaga decyzji.
- Ilustracja nie jest dowodem otwartej bramy ani ruchu robota; komunikaty pozostają zgodne z API.

## Pliki i pochodzenie

- `app/src/main/res/drawable/ic_launcher.xml`: edytowalny wektor domu z rozbłyskiem, bez zewnętrznego logo.
- `app/src/main/res/drawable-nodpi/art_gate.png`: wygenerowana ilustracja wejścia/bramy.
- `app/src/main/res/drawable-nodpi/art_cleaning.png`: wygenerowany robot dla Full Cleaning.
- `app/src/main/res/drawable-nodpi/art_mop.png`: wariant tego robota z wodną kroplą dla Full Mop.
- `docs/images/menu-v3.png`: rzeczywisty zrzut emulatora, bez prywatnej konfiguracji.
- `docs/images/menu-dudu7.png`: finalny zrzut z fizycznego radia DUDU7, użyty w README.
- `docs/images/routine-success-v3.png`: ekran wyniku z testu syntetycznego, nie dowód wykonania rutyny na robocie.

Grafiki rasterowe powstały wbudowanym narzędziem image generation (nie CLI), zachowują kanał
przezroczystości i są skopiowane do repozytorium. Nie wykorzystano zdjęć domu użytkownika,
map, danych lokalizacyjnych ani zrzutów prywatnej aplikacji. Robot jest ilustracją, nie
deklaracją fotograficznej zgodności z konkretnym modelem. Oryginały zachowano poza projektem.

## Prompty finalnych materiałów

### Brama

Use case: stylized-concept. Asset type: production illustration for a large touch tile in a
car head-unit home automation app. Create a premium, beautifully crafted 3D miniature of a
modern home entrance with an OPEN sliding driveway gate: two simple porcelain gate posts,
thick horizontal pale-metal slats slid to the side, a small welcoming roof silhouette behind,
a curved clear driveway. Close-up three-quarter orthographic view, generous margins, all
objects fully within frame. Rounded precision-machined shapes, soft ceramic and frosted glass,
muted apricot and ivory materials with deep midnight-blue details. Beautiful studio lighting,
subtle realistic contact shadow only. Genuinely transparent background, no floor plate,
no enclosing square, no backdrop, no words, no letters, no numbers, no brand logos, no watermark.
Distinct readable silhouette at small size; no tiny ornament, no trees, no car. Render at
highest available quality, square composition.

### Full Cleaning

Use case: stylized-concept. Final production illustration for an automotive home-control app's
Full Cleaning tile. A single white round robot vacuum, small lidar turret, midnight blue bumper,
pearl ceramic surfaces and restrained mint-teal accent, viewed three-quarter orthographic,
fully visible centered with generous margin. Two small clean sparkles. Premium tactile
industrial-design render, soft studio light, crisp silhouette readable when small. Genuine
transparent background, soft contact shadow only, no floor or scene, no words, no brand or
watermark. Highest available quality.

### Full Mop — edycja ilustracji Full Cleaning

Use case: precise-object-edit. Edit target: the attached robot vacuum production illustration.
Create its matching Full Mop routine illustration: preserve the same white robot shape,
angle, framing, generous margins, studio lighting and genuine transparent background. Change
mint accents to cool sky blue. Replace both floating sparkles with one clearly readable large
glossy blue water droplet just above the robot, fully inside frame. Show a subtle short clean
watery swoosh beside its base, not a puddle. No words, no logos, no extra objects. This is a
distinct companion tile for manual mopping in the same app. Highest quality, keep transparent alpha.
