# random_mining

블럭의 경도가 주기적으로 무작위로 재배치되고, **채굴을 도중에 취소하면 즉사하는** Paper 1.21.11 플러그인.

---

## 규칙

1. 모든 블럭 종류에 무작위 경도 배율이 배정된다. 돌이 흑요석보다 단단할 수도 있고, 그 반대일 수도 있다.
2. 배율은 일정 주기(기본 2분)마다 전부 새로 뽑힌다. 재배치되면 타이틀·소리로 알려준다.
3. 한 번 좌클릭으로 캐기 시작한 블럭은 **끝까지 캐야 한다.** 마우스를 떼거나, 다른 블럭으로 조준을 옮기거나,
   시선을 돌리는 순간 벌이 떨어진다.
4. 벌은 **포기한 본인이 받지 않는다.**
   - `100 / (대상 인원 + 1)` % 확률로 **전원 사망**. 5명이면 16.7%, 10명이면 9.1%.
   - 아니면 본인을 뺀 나머지 중 **한 명이 룰렛으로 뽑혀** 죽는다. 화면 가운데 이름이
     촤르륵 돌다가 점점 느려지고, 모루 소리와 함께 딱 멈추면서 죽을 사람이 확정된다.
   - 서버에 혼자뿐이라 대신 죽을 사람이 없으면 그때는 본인이 죽는다.

---

## 동작 원리

이 플러그인이 성립하려면 "채굴을 취소했다"는 신호를 서버가 확실히 받아야 한다. 그런데 마인크래프트
클라이언트는 채굴 진행도를 스스로 계산해서, 다 채우면 취소가 아니라 **완료** 패킷을 보낸다.
그래서 세 단계로 처리한다.

| 단계 | 담당 | 하는 일 |
|---|---|---|
| 1 | `BreakSpeedLock` | 플레이어의 `generic.block_break_speed` 어트리뷰트를 0으로 만든다. 이 어트리뷰트는 클라이언트에도 동기화되므로(`setSyncable(true)`), 클라이언트가 진행도를 **한 톨도** 쌓지 못한다. 즉 채굴 완료 패킷이 원천적으로 나올 수 없고, 마우스를 떼면 반드시 취소 패킷만 날아온다. |
| 2 | `MiningManager` | 매 틱 진행도를 직접 굴리고, 균열 애니메이션을 직접 그려주고, 다 차면 `BlockBreakEvent`를 발생시킨 뒤 직접 블럭을 부순다. |
| 3 | `MiningListener` | 취소 패킷(`BlockDamageAbortEvent`)이 오면 `PunishmentService` 로 넘긴다. |
| 4 | `PunishmentService` | 전원 사망 판정을 굴리고, 아니면 룰렛을 돌려 희생자를 뽑아 죽인다. |

채굴 시간은 바닐라 공식(`Player#getDestroySpeed` × `getDestroyProgress`)을 그대로 재현한 뒤 배율을 곱한다.
도구 등급, 효율 인챈트, 성급함/채굴 피로, 물속, 공중 부양 페널티가 전부 바닐라와 똑같이 반영된다.

### 주의할 점

- 크리에이티브·관전 모드, `randommining.bypass` 권한 보유자, 비활성 월드에서는 잠금이 걸리지 않고
  완전히 바닐라로 동작한다. **이들은 룰렛 후보에서도 빠지고 전원 사망에도 휘말리지 않는다.**
  확률 계산의 "대상 인원"도 이 사람들을 뺀 수다.
- `exempt-materials`에 넣은 블럭도 **채굴 자체는 이 플러그인이 처리한다.** 바닐라 채굴이 잠겨 있어서
  그러지 않으면 영영 부술 수 없는 블럭이 되기 때문이다. 대신 바닐라 속도로 캐지고 즉사 판정이 없다.
- 기반암처럼 원래 부술 수 없는 블럭은 그대로 부술 수 없다.
- 풀·횃불처럼 바닐라 경도가 0인 블럭은 즉시 파괴되고 즉사 판정에서 빠진다
  (`instant-break-max-vanilla-hardness`로 조정).
- 플러그인을 끄면(`onDisable`) 어트리뷰트 잠금을 전부 회수한다. 트랜지언트 모디파이어라
  서버가 비정상 종료돼도 플레이어 데이터에 남지 않는다.

---

## 명령어

| 명령어 | 권한 | 설명 |
|---|---|---|
| `/rm info [블럭]` | `randommining.use` | 보고 있는 블럭(또는 지정한 블럭)의 현재 경도와 예상 채굴 시간 |
| `/rm list [hard\|soft] [개수]` | `randommining.use` | 가장 단단한 / 무른 블럭 순위 |
| `/rm reroll` | `randommining.admin` | 지금 즉시 경도 재배치 |
| `/rm reload` | `randommining.admin` | `config.yml` 다시 읽기 |
| `/rm toggle` | `randommining.admin` | 플러그인 로직 on/off |

별칭: `/randommining`, `/rmine`, `/랜덤채굴`

권한 `randommining.bypass`를 가지면 랜덤 경도와 즉사 판정에서 완전히 제외된다(기본 false).

---

## 설정 (`config.yml`)

주요 항목만 추린다. 전체 설명은 파일 안 주석 참고.

```yaml
hardness:
  mode: PER_MATERIAL          # PER_MATERIAL(종류별) | PER_BLOCK(좌표별, 극한)
  reroll-interval-seconds: 120
  min-multiplier: 0.15        # 로그 균등 분포 — 0.2배와 5배가 비슷한 빈도로 나온다
  max-multiplier: 10.0
  min-break-ticks: 4          # 20틱 = 1초
  max-break-ticks: 1200

death:
  enabled: true
  grace-ticks: 3              # 오클릭 봐주는 시간. 0 = 무자비 모드
  roulette:
    enabled: true
    everyone-chance: 100      # 전원 사망 확률(%) = 이 값 / (대상 인원 + 1)
    spin-ticks: 60            # 룰렛이 도는 총 길이. 기본값이면 19프레임 / 약 3.2초
    min-interval-ticks: 2     # 이름이 바뀌는 간격. min → max 로 점점 느려진다
    max-interval-ticks: 9
  messages:
    self:     "... <player> ..."            # 혼자라 본인이 죽을 때
    victim:   "... <player> ... <victim> ..."   # 대신 한 명이 죽을 때
    everyone: "... <player> ... <victims> ..."  # 전원 사망
```

`roulette.enabled: false` 로 두면 연출 없이 즉시 처리된다. 룰렛이 도는 중에 또 포기하면
타이틀이 겹치므로, 두 번째부터는 연출 없이 바로 집행된다.

전원 사망일 때는 인원수만큼 같은 사망 메시지가 도배되지 않도록 개별 메시지를 지우고
`everyone` 한 줄만 방송한다. 죽은 사람 이름은 `<victims>` 에 전부 들어간다.

`PER_BLOCK` 모드는 좌표 하나하나에 다른 경도를 준다. 시드와 좌표를 splitmix64로 섞어
그때그때 계산하므로 블럭 수와 무관하게 메모리를 쓰지 않는다.

---

## 빌드 / 테스트

```bash
./gradlew build
```

산출물: `build/libs/random_mining-1.0-SNAPSHOT.jar`

빌드 후 서버 `plugins` 폴더까지 한 번에:

```bash
./gradlew deploy
```

기본 목적지는 `build.gradle` 위쪽 `serverPluginsDir` 에 적혀 있다. 다른 서버에 넣으려면:

```bash
./gradlew deploy -PserverPluginsDir=D:/내서버/plugins
```

버전이 바뀌면 `plugins` 안에 남아 있는 예전 `random_mining-*.jar` 를 지우고 새 것만 남긴다.
서버가 켜진 채로 덮어써도 실패하지는 않지만, 새 코드는 재시작해야 적용된다.

테스트 서버 실행 (Paper 1.21.11을 자동으로 받아 `run/`에 띄운다):

```bash
./gradlew runServer
```

> Paper 1.21.11 은 Java 21 이상이 필요하다. `java -version` 이 17 이하면 서버가
> `UnsupportedClassVersionError` 로 죽는다.

---

## 문제 해결

### OP 가 아니면 채굴이 아예 안 된다

`server.properties` 의 **`spawn-protection`** 을 확인할 것. 0 이 아니면 스폰 주변
`(radius*2+1)²` 구역에서 OP 가 아닌 플레이어의 채굴이 통째로 막힌다.

원인은 이 플러그인과 스폰 보호가 맞물리는 방식에 있다. 보호 구역 안에서 OP 가 아닌
플레이어가 좌클릭하면 `ServerPlayerGameMode#handleBlockBreakAction` 이
`mayInteract` 검사에서 **`BlockDamageEvent` 를 발생시키기도 전에 return** 한다.
플러그인은 채굴이 시작된 사실조차 모르고, 한편 `block_break_speed` 는 0 으로 잠겨 있으니
바닐라 채굴도 안 된다. 그래서 "좌클릭해도 아무 일도 안 일어나는" 상태가 된다.

```properties
spawn-protection=0
```

로 두고 서버를 재시작하면 해결된다. 플러그인은 시작할 때 이 값이 0 이 아니면 콘솔에
경고를 찍고, 보호 구역에서 좌클릭한 플레이어에게는 액션바로 이유를 알려준다.
