# Notatki techniczne DUDU/SYU IPC

> Dudu Home uses the hardware-tested calling implementation described below.
> Private calibration tools and location data are not distributed in this repository.
> The IPC protocol below is preserved in `0.2.0-local`. The current menu, setup and background
> service extend the original baseline; see [functional contract](FUNCTIONAL.md) and
> [Roborock architecture](ROBOROCK.md). A cleanup notification now releases the shared action
> lease only after Binder cleanup; dial/hangup protocol and safety rules are unchanged.

## Pochodzenie ustaleń

Kontrakt tego PoC pochodzi z przekazanych wyników statycznej analizy oficjalnego obrazu
DUDUOS 3.7 `260210` dla DUDU7. Analiza obejmowała manifesty i zdekompilowany kod pakietów
DUDU/SYU. Szczegóły komend DUDU, kodów callbacków i wariantu BLINK typu 7 pochodzą z tej
analizy firmware.

Projekt FytBt stanowi niezależne, społecznościowe potwierdzenie, że platforma FYT/UIS7870
udostępnia moduły przez ten sam wzorzec raw Binder: toolkit, `getRemoteModule`, rejestracja
callbacku i tablice w Parcel. FytBt dotyczy pokrewnego urządzenia FYT, a nie testu tego APK
na DUDU7.

Ta implementacja została skompilowana i uruchomiona na standardowym emulatorze Androida.
2026-09-04 wykonano także testy na fizycznym DUDU7 z DUDUOS 3.7 `260210`: pobrano
manifest i APK z zainstalowanego `com.syu.ms`, poprawiono nazwy komponentów, potwierdzono
ścieżkę Toolkit, bezpieczny stan odłączonego telefonu oraz pełny przebieg dial/hangup z
jednym połączonym telefonem.

## Dlaczego standardowe Android API nie działa

DUDU7 obsługuje telefon przez zewnętrzny, sprzętowy moduł Bluetooth zarządzany przez
pakiety SYU. Rozmowa nie przechodzi przez standardowy Android Telecom radia. W analizowanym
`com.syu.bt` nie ma starego `PhoneActivity`, obsługi `tel:`, roli domyślnego dialera ani
ścieżki `ACTION_CALL`.

Z tego powodu PoC celowo nie używa:

- `Intent.ACTION_CALL`
- `Intent.ACTION_DIAL`
- `TelecomManager`
- `BluetoothHeadsetClient`
- Accessibility
- automatycznego klikania UI aplikacji BT Phone

Połączenie jest komendą do modułu Bluetooth radia, a faktycznie wykonuje je sparowany
telefon ze swojej karty SIM.

## Usługi Binder

### Ścieżka preferowana

| Element | Wartość |
|---|---|
| package | `com.syu.ms` |
| component | `com.syu.ms/app.ToolkitService` |
| class name | `app.ToolkitService` |
| action | `com.syu.ms.toolkit` |
| descriptor | `com.syu.ipc.IRemoteToolkit` |
| transaction | `1` |
| wywołanie | `getRemoteModule(2)` |
| module ID | `2`, Bluetooth |

Parcel dla `getRemoteModule(2)`:

```text
writeInterfaceToken("com.syu.ipc.IRemoteToolkit")
writeInt(2)
transact(1, data, reply, 0)
reply.readException()
reply.readStrongBinder()
```

### Fallback bezpośredni

| Element | Wartość |
|---|---|
| package | `com.syu.ms` |
| component | `com.syu.ms/app.ModuleService` |
| class name | `app.ModuleService` |
| action | `com.syu.ms.bt` |
| descriptor zwracanego Binder | `com.syu.ipc.IRemoteModule` |

Fallback jest sekwencyjny. Klient najpierw całkowicie kończy nieudany bind Toolkit, a
dopiero potem próbuje Module. Po rejestracji callbacków lub po dial nie istnieje żaden
fallback, reconnect ani redial.

Manifest APK pobrany z fizycznego DUDU7 potwierdził klasy `app.ToolkitService` i
`app.ModuleService`, ich intent filters oraz brak `android:permission` na deklaracjach
usług. Początkowe nazwy `com.syu.ms.app.*` były niezgodne z rzeczywistym manifestem i
powodowały natychmiastowe `bindService == false`.

Explicit bind z sideloaded APK do `app.ToolkitService` został potwierdzony na DUDUOS
`260210`. Toolkit zwrócił moduł Bluetooth `2` bez dodatkowego handshake. Fallback do
`app.ModuleService` nie został wykonany, ponieważ preferowana ścieżka zakończyła się
powodzeniem.

## IRemoteModule

Descriptor:

```text
com.syu.ipc.IRemoteModule
```

| Transaction | Znaczenie |
|---:|---|
| `1` | `cmd(int, int[], float[], String[])` |
| `3` | `register(callback, updateCode, updateNow)` |
| `4` | `unregister(callback, updateCode)` |

Parcel command:

```text
writeInterfaceToken("com.syu.ipc.IRemoteModule")
writeInt(commandCode)
writeIntArray(ints)
writeFloatArray(floats)
writeStringArray(strings)
transact(1, data, reply, 0)
reply.readException()
```

Brak argumentu jest kodowany jako `null`, nie jako pusta tablica. Wszystkie Parcel są
zwalniane w `finally`, a fałszywy wynik `transact` jest błędem protokołu.

Register zapisuje kolejno token, Binder callbacku, `updateCode` i `updateNow`. Ten PoC
używa `updateNow = 1`. Transakcja `3` jest wysyłana z `IBinder.FLAG_ONEWAY`, zgodnie ze
ścieżką odtworzoną ze stockowego `com.fyt.screenbutton` i niezależną implementacją FytBt.
Unregister zapisuje token, ten sam Binder callbacku i `updateCode`, również jako one-way.
Flaga transaction `4` jest symetrycznym założeniem, które nadal wymaga potwierdzenia na
docelowym firmware.

## IModuleCallback

Descriptor:

```text
com.syu.ipc.IModuleCallback
```

Callback używa transaction `1`:

```text
data.enforceInterface("com.syu.ipc.IModuleCallback")
updateCode = data.readInt()
ints = data.createIntArray()
floats = data.createFloatArray()
strings = data.createStringArray()
```

Własna klasa `Binder` obsługuje także `INTERFACE_TRANSACTION`. Dla wywołania
synchronicznego zapisuje `writeNoException()`, a dla one-way poprawnie toleruje
`reply == null`. Dane są przekazywane na pojedynczy `HandlerThread`, więc callback Binder
nie dotyka UI i nie wykonuje logiki sesji równolegle.

## Callbacki i stany

Callbacki są rejestrowane przed dial w kolejności `6`, `61`, `9`.

### Globalny callback `9`

| `ints[0]` | Znaczenie |
|---:|---|
| `0` | telefon odłączony lub niedostępny |
| `1` | łączenie |
| `2` | telefon połączony, brak rozmowy |
| `3` | połączenie wychodzące |
| `4` | połączenie przychodzące |
| `5` | aktywna rozmowa |
| `6` | parowanie |
| `7` | wiele rozmów lub stan złożony |

Nieznana wartość nie jest traktowana jako idle. Kończy próbę z `IPC_ERROR`.

### Wielourządzeniowe callbacki DUDU7

- update `6`: na fizycznym DUDU7 z `260210` jest listą `strings = [MAC, ...]`; statycznie
  znaleziony wariant zgodności `strings = [deviceId, MAC]` pozostaje obsługiwany
- update `61`: `strings[0] = deviceId`, `ints[0] = stan urządzenia`

Mapowanie per-device:

| Kod `61` | Stan globalny | Znaczenie |
|---:|---:|---|
| `1` | `0` | disconnected |
| `2` | `1` | connecting |
| `3` | `2` | idle |
| `4` | `3` | outgoing |
| `5` | `4` | incoming |
| `6` | `5` | active |

Callback `61` może przyjść przed `6`, dlatego mapy `deviceId -> state` oraz
`deviceId -> MAC` są scalane niezależnie. Po rejestracji klient zawsze zbiera snapshot
przez pełne `BIND_TIMEOUT_MS = 3000`. Ma to zapobiec wybraniu wariantu single-device,
zanim nadejdą dane wielourządzeniowe.

W teście fizycznym z odłączonym telefonem callbacki zostały poprawnie zarejestrowane przez
ścieżkę Toolkit. Bezpośrednio po rejestracji przyszedł update `6` bez kompletnego mapowania,
a następnie update `9` ze stanem `0`. Po pełnych 3 sekundach snapshotu aplikacja zakończyła
próbę jako `PHONE_NOT_CONNECTED`, bez dial i hangup.

Z jednym połączonym telefonem update `6` miał dokładnie jeden 12-znakowy MAC, update `9`
miał stan `2`, a update `61` nie pojawił się w początkowym snapshotcie ani w udanym
przebiegu. Kształt payloadu jest logowany bez wartości: liczby elementów, długości napisów
i wynik walidacji MAC.

## Wybór telefonu

1. Globalny stan musi nadal wynosić dokładnie `2`.
2. Jakikolwiek stan rozmowy przed dial daje `PHONE_BUSY` bez hangup.
3. Łączenie lub parowanie daje `PHONE_NOT_CONNECTED`.
4. Dokładnie jeden per-device idle z klasycznym mapowaniem `deviceId -> MAC` wybiera
   wariant zgodności z MAC.
5. Więcej niż jeden per-device idle daje `MULTIPLE_PHONES`.
6. Potwierdzony na `260210` format listy MAC wybiera wariant single-device tylko przy
   dokładnie jednym MAC oraz globalnym stanie `2` po pełnym snapshotcie.
7. Więcej niż jeden MAC bez stanów daje `MULTIPLE_PHONES`.
8. Brak danych wielourządzeniowych przy jednoznacznym globalnym stanie `2` także dopuszcza
   wariant single-device dla zgodności ze starszym firmware.
9. Niepełne lub sprzeczne dane kończą się błędem, a nie zgadywaniem.

MAC jest normalizowany do 12 znaków hex bez dwukropków i walidowany wzorcem
`[0-9A-F]{12}`. Wybrany telefon i wariant są zamrożone przed dial. Hangup używa dokładnie
tego samego wariantu i urządzenia.

## Dial i hangup

### Potwierdzony wariant DUDUOS 3.7 `260210`

Dial:

```text
command 7
strings = [ZAPISANY_NUMER_BRAMY]
```

Hangup:

```text
command 10
strings = null
```

Systemowy log potwierdził odebranie obu komend. Callback `9` przeszedł kolejno przez
`2 -> 3 -> 2`, dzięki czemu potwierdzono outgoing, rozłączenie i końcowe idle.
Wcześniejsza kontrolna próba `[MAC, numer]` nie wywołała outgoing. Analiza APK
`com.syu.ms` pobranego z radia wykazała, że przy `mRdaParser = null` obsługa command `7`
przekazuje dalej wyłącznie `strings[0]`, natomiast command `10` wywołuje bezargumentowe
rozłączenie. To jest podstawa wyboru wariantu single-device dla formatu listy MAC.

### Wariant zgodności z MAC - nieużywany na przetestowanym radiu

Dial:

```text
command 7
strings = [MAC_BEZ_DWUKROPKÓW, ZAPISANY_NUMER_BRAMY]
```

Hangup:

```text
command 10
strings = [MAC_BEZ_DWUKROPKÓW]
```

Po wysłaniu dial wariant nie jest zmieniany. Brak callbacku nie powoduje próby drugiego
dial. Starszy hangup `command 6`, `ints = [1]` nie jest używany.

Flaga odpowiedzialności za dial jest ustawiana bezpośrednio przed transaction. Dzięki
temu nawet niejednoznaczny wyjątek po wysłaniu Parcel pozwala wykonać tylko jedno
best-effort hangup. Sukces samej transaction oznacza wyłącznie przyjęcie IPC, nie sukces
rozmowy.

## Lokalna konfiguracja numeru

APK nie zawiera numeru bramy. `GateNumberStore` przyjmuje opcjonalny początkowy `+`, od 3
do 15 cyfr oraz typowe separatory wizualne. Przed zapisem usuwa spacje, nawiasy i myślniki.
Niepoprawny lub brakujący numer blokuje `GateCallCoordinator` i pokazuje formularz.
W baseline poprawny zapis wyświetlał potwierdzenie i zamykał Activity; kolejny start dzwonił.
W aktualnym menu zapis wraca do menu, narzuca trwałą blokadę 60 sekund i nie tworzy koordynatora.
Upływ czasu niczego nie uruchamia: wymaga nowego kliknięcia lub nowego zdarzenia.

Znormalizowany numer jest zapisany w prywatnym `SharedPreferences` o nazwie
`gate_settings`. `android:allowBackup="false"` oraz reguły data extraction wykluczają ten
plik z backupu i transferu urządzenia. W aktualnej wersji numer zmienia się przez Ustawienia;
nie czyścimy danych radia, ponieważ usunęłoby to również pozostałą konfigurację i blokady.
Logi nie pokazują numeru ani jego fragmentów.

Ten sam prywatny plik przechowuje czas ostatniego rozpoczęcia dial. Przed transaction
`GateNumberStore.reserveDial()` synchronicznie rezerwuje próbę albo ją odrzuca. Blokada
trwa `REATTEMPT_COOLDOWN_MS = 60000` i działa po zamknięciu procesu, więc osobne ponowne
uruchomienie przez launcher nie może szybko wysłać kolejnego dial. Wstępna kontrola w
Activity służy tylko do pokazania czytelnego komunikatu; kontrola przy transaction jest
wiążącą regułą bezpieczeństwa. Cofnięcie zegara powoduje pełną 60-sekundową blokadę od
chwili wykrycia, zamiast blokady bez końca.

## Maszyna stanów i timeouty

Nominalny przebieg:

```text
STARTING
  -> BINDING
  -> CHECKING_PHONE
  -> READY
  -> DIAL_REQUESTED
  -> OUTGOING
  -> WAITING_BEFORE_HANGUP
  -> HANGING_UP
  -> SUCCESS
```

Każdy błąd prowadzi do `ERROR`. Sukces wymaga własnego dial, późniejszego outgoing i
jeszcze późniejszego idle. Idle odebrany przed outgoing nie jest sukcesem.

| Stała | Wartość | Cel |
|---|---:|---|
| `REATTEMPT_COOLDOWN_MS` | `60000` | trwała blokada kolejnego dial po poprzedniej próbie |
| `BIND_TIMEOUT_MS` | `3000` | limit aktywnej ścieżki bind i odczytu snapshotu |
| `DIAL_START_TIMEOUT_MS` | `5000` | oczekiwanie na outgoing po dial |
| `HANGUP_DELAY_MS` | `5000` | czas od outgoing do hangup |
| `HANGUP_CONFIRM_TIMEOUT_MS` | `5000` | oczekiwanie na idle po hangup |
| `TOTAL_ATTEMPT_TIMEOUT_MS` | `25000` | limit całej próby |

Jeżeli po outgoing telefon sam wróci do idle przed planowanym hangup, próba kończy się
sukcesem bez dodatkowego hangup. Jeżeli po outgoing pojawi się active, hangup jest wysyłany
natychmiast. Dial timeout wykonuje jedno best-effort hangup i pokazuje `DIAL_TIMEOUT`.

## Ograniczenie pierwszego sygnału

Prywatne IPC nie udostępnia osobnego stanu HFP `ALERTING`. Globalny stan `3` nie pozwala
odróżnić wybierania numeru, oczekiwania na sieć i ringback. PoC liczy
`HANGUP_DELAY_MS = 5000` od pierwszej obserwacji outgoing. Nie analizuje dźwięku. UI i
dokumentacja nie twierdzą, że wykryto pierwszy sygnał ani otwarcie bramy.

## Permissions i package visibility

Manifest nie deklaruje `CALL_PHONE`, `READ_PHONE_STATE`, `BLUETOOTH_CONNECT`,
`BLUETOOTH_SCAN`, `BLUETOOTH_PRIVILEGED` ani `QUERY_ALL_PACKAGES`. Nie są potrzebne root,
Shizuku, podpis systemowy ani instalacja `priv-app`.

Wpis visibility wymagany dla połączeń to (aktualna wersja deklaruje dodatkowo pakiet Yanosika):

```xml
<queries>
    <package android:name="com.syu.ms" />
</queries>
```

Powodem jest bezpośredni bind do eksportowanej usługi vendorowej, nie użycie standardowego
Bluetooth API Androida. Brak kontroli uprawnień po stronie analizowanego firmware jest
własnością tej wersji DUDUOS, nie gwarancją dla przyszłego OTA. Na fizycznym DUDU7 z
`260210` sideloaded APK skutecznie wykonało bind do `ToolkitService` bez dodatkowego
permission.

## Utrata usługi i cykl życia

Klient rejestruje `IBinder.DeathRecipient`, obsługuje `onServiceDisconnected`,
`onBindingDied` i `onNullBinding`. Utrata usługi nie powoduje reconnect ani redial.
Callbacki są wyrejestrowywane best-effort, Binder odwiązywany, timeouty anulowane, a
procesowa blokada zwalniana.

Przy zwykłym `onDestroy` po własnym dial koordynator próbuje hangup przed cleanup. Nagłe
ubicie procesu, restart radia i odcięcie zasilania nie dają gwarancji wykonania tej ścieżki.
Sam koordynator rozmowy nie jest foreground service. Obecne rozszerzenie ma oddzielną
`HomeMonitorService` dla lokalizacji; nie zmienia to ograniczeń cleanup rozmowy.

`getRemoteModule` oraz komendy dial i hangup są synchronicznymi transakcjami Binder
wykonywanymi poza głównym wątkiem. Android nie udostępnia limitu czasu pojedynczego
`transact`. Jeżeli usługa vendorowa zawiesi się wewnątrz takiego wywołania, timeout sesji i
cleanup cyklu życia mogą wykonać się dopiero po powrocie transakcji. To ryzyko wymaga
obserwacji na fizycznym radiu.

## Co można sprawdzić na Macu

Standardowy emulator potwierdza:

- build i debug signing
- instalację i automatyczny start
- poziomy layout i nietypowe proporcje
- bezpieczny błąd przy braku `com.syu.ms`
- działanie `Ponów`, `Zamknij` i logów `DuduGate`

Nie potwierdza żadnego udanego elementu vendor IPC. Android Automotive Emulator także nie
ma pakietu SYU ani sprzętowego modułu DUDU.

## Otwarte pytania do dalszych testów na DUDU7

Ścieżka `ToolkitService`, `getRemoteModule(2)`, rejestracja callbacków, bezpieczny stan
odłączonego telefonu oraz pełna sekwencja single-device `idle -> dial -> outgoing ->
hangup -> idle` są potwierdzone na `260210`. Nadal pozostają otwarte:

1. Czy fallback `ModuleService` jest dostępny dla sideloaded APK, gdy Toolkit faktycznie
   zawiedzie?
2. Czy transaction `4` unregister na DUDUOS 3.7 także wymaga flagi one-way, tak jak
   potwierdzony transaction `3` register?
3. Kiedy i w jakim dokładnym formacie emitowany jest update `61`?
4. Czy update `6` obejmuje tylko połączone telefony, czy także zapamiętane urządzenia?
5. Czy po hangup zawsze przychodzi idle `2`, również przy słabym zasięgu i odrzuceniu numeru?
6. Jak zachowuje się moduł przy dwóch równocześnie połączonych telefonach?
7. Czy wariant z MAC jest używany na innym typie modułu, gdy `mRdaParser` nie jest `null`?
8. Czy OTA zmienia descriptory, transaction codes, komponenty, format callbacków albo
   wymagania permissions?

Pierwszy test powinien zachować pełne `adb logcat -s DuduGate` od startu do błędu lub
sukcesu. Przy problemie nie należy dodawać automatycznego drugiego dial.

## Źródła

- [Oficjalny DUDUOS 3.7, wydanie 260210](https://forum.dudu-auto.com/d/3099-duduos-37-experience-the-evolution-official-release-now-rolling-out-260131)
- [DUDU7 Bluetooth Phone bez standardowego dialera](https://forum.dudu-auto.com/d/4082-dudu-7-bluetooth-phone-calls-without-android-auto)
- [Opis zewnętrznego modułu Bluetooth DUDU](https://forum.dudu-auto.com/d/2319-no-bt-sound-with-250905)
- [Brak standardowego ACTION_CALL](https://forum.dudu-auto.com/d/1412-duduos-36-official-release-elevate-your-driving-experience250515?page=17)
- [Repozytorium FytBt](https://github.com/PimpinPumpkin/FytBt)
- [FytBt FINDINGS.md](https://github.com/PimpinPumpkin/FytBt/blob/main/FINDINGS.md)
- [FytBt SyuLink.kt](https://github.com/PimpinPumpkin/FytBt/blob/main/app/src/main/kotlin/com/fytbt/media/SyuLink.kt)
- [Android Service manifest, exported i permission](https://developer.android.com/guide/topics/manifest/service-element)
- [Ukryty systemowy Android HFP Client](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothHeadsetClient.java)

Źródła społecznościowe potwierdzają ogólny sposób komunikacji Binder na platformie FYT.
Nie zastępują testu poleceń telefonicznych na konkretnym firmware DUDU7.
