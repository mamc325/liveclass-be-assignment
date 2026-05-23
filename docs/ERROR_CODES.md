# BE-A 수강 신청 시스템 예외 코드 카탈로그

> 대상: 라이브클래스 BE-A 채용 과제

---

## 1. 문서 목적

본 문서는 API 응답의 `code` 필드 값들과 그에 매핑되는 도메인 예외, HTTP 상태, 메시지 템플릿, 발생 조건을 **단일 진실 원천(SSoT)** 으로 정리한다.

다른 문서에 산발적으로 등장하는 에러 표현은 모두 본 카탈로그를 참조한다.

- API 인터페이스 → `docs/API.md`
- 상태 전이별 발생 위치 → `docs/STATE_TRANSITIONS.md`
- DB unique violation 매핑 → `docs/CONCURRENCY.md` 9.5
- **에러 코드 정의·매핑·구현 정책 → 본 문서**

---

## 2. 설계 선택: enum 기반

### 2.1 검토한 대안

| 옵션 | 설명 | 평가 |
|---|---|---|
| **A. enum 기반 (선택)** | `ErrorCode` enum + 단일 `DomainException` 클래스 | 코드 한곳 catalog. 추가는 enum 상수 추가만. ControllerAdvice가 단일 handler |
| B. 클래스 계층 | 추상 `DomainException` + 코드별 구체 클래스 다수 | catch 시 세분화 가능. 클래스 수 폭증 |

### 2.2 A안을 선택한 이유

1. **코드 수가 많지 않아 enum 한 파일로 충분히 가독성을 확보**할 수 있다.
2. **catch 분기가 필요 없는 도메인**. 도메인 예외는 컨트롤러까지 propagate되어 `@ControllerAdvice`가 일괄 처리 — 중간 레이어에서 코드별로 다르게 처리할 일이 없다.
3. **추가 비용이 적다**. 새 코드는 enum 상수 한 줄 추가로 끝.
4. **메시지 템플릿/HTTP 상태/title을 enum 속성으로 묶어 관리**할 수 있어 catalog 정의와 매핑이 한 곳에 응집.

### 2.3 클래스 계층을 선택하지 않은 이유

- 도메인 예외가 풍부한 메서드/상태를 가질 일이 없다. 단순히 "어떤 `ErrorCode`로 응답할지"만 결정하면 됨.
- catch 시 분기 처리가 필요한 시나리오가 없다. 모든 도메인 예외는 동일 핸들러로 처리되어 ProblemDetail로 변환된다.
- 클래스 수가 많이 추가되면 패키지 비대화 + 보일러플레이트 증가.

### 2.4 도입하지 않은 코드와 그 이유

다음 코드는 의도적으로 정의하지 않았다. 통합 대상과 미정의 사유를 명시.

| 미정의 코드 | 통합 대상 | 사유 |
|---|---|---|
| `USER_NOT_FOUND` | `UNAUTHENTICATED` | 본 과제는 `X-USER-ID` 기반 간이 인증을 사용한다. 헤더가 없거나 해당 사용자가 존재하지 않는 경우 모두 "유효하지 않은 호출자"로 의미가 동일하므로 `UNAUTHENTICATED`로 통합한다. 실제 인증 시스템 도입 시 분리 가능 |
| `CREATOR_ONLY` / `STUDENT_ONLY` | `FORBIDDEN` | 역할 불일치를 코드별로 세분화하면 클라이언트 분기는 늘어나지만 사용자 경험은 동일("권한이 없습니다"). 역할별 상세 설명은 `detail` 메시지로 제공 |
| `LOCK_TIMEOUT` | (없음) | `lock_timeout` 자체를 본 과제 구현 범위에서 설정하지 않음 (`docs/CONCURRENCY.md` 8.4 참조). 운영 확장 시 검토 |

---

## 3. ErrorCode enum 카탈로그

### 3.1 전체 카탈로그

총 17개 코드.

| code | HTTP | title | 기본 메시지 | 발생 위치 |
|---|---|---|---|---|
| `INVALID_REQUEST` | 400 | Invalid Request | 요청이 유효하지 않습니다. | 입력 검증 실패 (Bean Validation, path/query param 타입 오류 등) |
| `UNAUTHENTICATED` | 401 | Unauthenticated | 인증이 필요합니다. | `X-USER-ID` 누락 또는 존재하지 않는 사용자 |
| `FORBIDDEN` | 403 | Forbidden | 권한이 없습니다. | role 불일치 (예: STUDENT가 CREATOR API 호출) |
| `NOT_COURSE_OWNER` | 403 | Not Course Owner | 본인 강의가 아닙니다. | 강의 상태 변경/수강생 목록 조회 시 소유권 위반 |
| `NOT_ENROLLMENT_OWNER` | 403 | Not Enrollment Owner | 본인 신청이 아닙니다. | 결제 확정/수강 취소 시 소유권 위반 |
| `NOT_WAITLIST_OWNER` | 403 | Not Waitlist Owner | 본인 대기가 아닙니다. | 대기 취소 시 소유권 위반 |
| `COURSE_NOT_FOUND` | 404 | Course Not Found | 강의를 찾을 수 없습니다. (id={id}) | 대상 Course 없음 |
| `ENROLLMENT_NOT_FOUND` | 404 | Enrollment Not Found | 신청을 찾을 수 없습니다. (id={id}) | 대상 Enrollment 없음 |
| `WAITLIST_NOT_FOUND` | 404 | Waitlist Not Found | 대기를 찾을 수 없습니다. (id={id}) | 대상 Waitlist 없음 |
| `INVALID_TRANSITION` | 409 | Invalid Transition | 허용되지 않는 상태 전이입니다. | **Course의 모집 상태 전이** 실패 (`DRAFT→OPEN`, `OPEN→CLOSED` 외 경로 시도) |
| `COURSE_NOT_OPEN` | 409 | Course Not Open | 강의가 모집 중 상태가 아닙니다. | 수강 신청/대기 등록 시 `course.status != OPEN` |
| `DUPLICATE_ENROLLMENT` | 409 | Duplicate Enrollment | 이미 활성 신청이 존재합니다. | 동일 사용자/강의 활성 신청 중복 (서비스 검증 또는 partial unique violation) |
| `DUPLICATE_WAITLIST` | 409 | Duplicate Waitlist | 이미 활성 대기가 존재합니다. | 동일 사용자/강의 활성 대기 중복 |
| `INVALID_STATUS` | 409 | Invalid Status | 현재 상태에서 허용되지 않는 작업입니다. | **Enrollment 또는 Waitlist의 현재 상태**가 요청 작업을 허용하지 않을 때 (결제 확정/대기 취소 가드 영향 0 등) |
| `ALREADY_CANCELLED` | 409 | Already Cancelled | 이미 취소된 항목입니다. | 수강 취소 시 락 후 재조회 결과 `CANCELLED` |
| `CANCEL_DEADLINE_EXCEEDED` | 409 | Cancel Deadline Exceeded | 취소 가능 기간을 초과했습니다. | `CONFIRMED` 취소 시 `confirmed_at + cancellation_deadline_days` 초과 |
| `INTERNAL_ERROR` | 500 | Internal Error | 서버 내부 오류가 발생했습니다. | 미처리 예외 또는 정상 흐름에서 발생 불가한 unique violation (코드 버그 시그널) |

**`INVALID_TRANSITION`과 `INVALID_STATUS`의 구분 기준**

두 코드 모두 409이지만 사용 도메인이 다르다.

| code | 사용 도메인 | 예시 |
|---|---|---|
| `INVALID_TRANSITION` | **Course** | `DRAFT → CLOSED` 직접 마감 시도, `CLOSED → OPEN` 역행 시도 |
| `INVALID_STATUS` | **Enrollment / Waitlist** | 이미 `CANCELLED`인 enrollment에 confirm 시도, 이미 `PROMOTED`인 waitlist에 cancel 시도 |

### 3.2 enum 정의 (구현 예시)

```java
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid Request", "요청이 유효하지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden", "권한이 없습니다."),
    NOT_COURSE_OWNER(HttpStatus.FORBIDDEN, "Not Course Owner", "본인 강의가 아닙니다."),
    NOT_ENROLLMENT_OWNER(HttpStatus.FORBIDDEN, "Not Enrollment Owner", "본인 신청이 아닙니다."),
    NOT_WAITLIST_OWNER(HttpStatus.FORBIDDEN, "Not Waitlist Owner", "본인 대기가 아닙니다."),
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Course Not Found", "강의를 찾을 수 없습니다."),
    ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Enrollment Not Found", "신청을 찾을 수 없습니다."),
    WAITLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "Waitlist Not Found", "대기를 찾을 수 없습니다."),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "Invalid Transition", "허용되지 않는 상태 전이입니다."),
    COURSE_NOT_OPEN(HttpStatus.CONFLICT, "Course Not Open", "강의가 모집 중 상태가 아닙니다."),
    DUPLICATE_ENROLLMENT(HttpStatus.CONFLICT, "Duplicate Enrollment", "이미 활성 신청이 존재합니다."),
    DUPLICATE_WAITLIST(HttpStatus.CONFLICT, "Duplicate Waitlist", "이미 활성 대기가 존재합니다."),
    INVALID_STATUS(HttpStatus.CONFLICT, "Invalid Status", "현재 상태에서 허용되지 않는 작업입니다."),
    ALREADY_CANCELLED(HttpStatus.CONFLICT, "Already Cancelled", "이미 취소된 항목입니다."),
    CANCEL_DEADLINE_EXCEEDED(HttpStatus.CONFLICT, "Cancel Deadline Exceeded", "취소 가능 기간을 초과했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String title;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String title, String defaultMessage) {
        this.status = status;
        this.title = title;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }
    public String defaultMessage() { return defaultMessage; }

    public DomainException asException() {
        return new DomainException(this);
    }

    public DomainException with(String customMessage) {
        return new DomainException(this, customMessage);
    }
}
```

`HttpStatus` enum 사용으로 magic number를 회피하고 Spring 생태계 표준 타입과 일치시킨다.

---

## 4. DomainException 클래스 설계

단일 예외 클래스가 `ErrorCode` 값을 들고 있다. 메시지는 `RuntimeException`의 기본 `message` 필드에 위임한다(별도 `customMessage` 필드를 두지 않아 단순함을 유지).

```java
public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

### 사용 패턴

```java
// 1) 기본 메시지로 던지기
throw ErrorCode.COURSE_NOT_OPEN.asException();

// 2) 컨텍스트 정보를 메시지에 포함
throw ErrorCode.COURSE_NOT_FOUND.with("강의를 찾을 수 없습니다. (id=" + courseId + ")");

// 3) 직접 생성
throw new DomainException(ErrorCode.CANCEL_DEADLINE_EXCEEDED);
```

핸들러에서는 `e.getMessage()`로 메시지를, `e.errorCode()`로 코드를 얻는다.

### 도메인 예외와 일반 RuntimeException의 구분

- 도메인 규칙 위반은 `DomainException`만 사용 (= 컨트롤러 단까지 propagate되어 `code`/`status`가 자동 결정됨)
- 인프라/외부 시스템 예외는 그대로 일반 `RuntimeException` → `Throwable` handler에서 `INTERNAL_ERROR` 500으로 매핑

---

## 5. ControllerAdvice 매핑 정책

`@RestControllerAdvice`에서 다음 핸들러들을 정의한다.

### 5.1 도메인 예외 매핑

```java
@ExceptionHandler(DomainException.class)
public ResponseEntity<ProblemDetail> handleDomain(DomainException e, HttpServletRequest req) {
    ErrorCode code = e.errorCode();
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        code.status(),
        e.getMessage()
    );
    problem.setTitle(code.title());
    problem.setInstance(URI.create(req.getRequestURI()));
    problem.setProperty("code", code.name());
    return ResponseEntity.status(code.status()).body(problem);
}
```

### 5.2 비도메인 예외 매핑

| 예외 | 매핑 코드 | HTTP | 분기 기준 |
|---|---|---|---|
| `MethodArgumentNotValidException` (request body Bean Validation 실패) | `INVALID_REQUEST` | 400 | detail에 필드별 위반 정보 포함 |
| `MethodArgumentTypeMismatchException` (path/query parameter 타입 오류) | `INVALID_REQUEST` | 400 | 잘못된 enum 값, 숫자 변환 실패 등 |
| `ConstraintViolationException` (서비스 레이어 `@Validated` 검증 실패) | `INVALID_REQUEST` | 400 | page/size 등 query param 검증 실패 |
| `BindException` (form/query parameter binding 실패) | `INVALID_REQUEST` | 400 | |
| `HttpMessageNotReadableException` (JSON 파싱 실패) | `INVALID_REQUEST` | 400 | |
| `MissingRequestHeaderException` (X-USER-ID 누락) | `UNAUTHENTICATED` | 401 | 헤더 이름이 `X-USER-ID`일 때 |
| `DataIntegrityViolationException` (DB 제약 위반) | constraint name 분기 → 5.4 참조 | 409 또는 500 | SQLState `23505` 기준 |
| `HttpRequestMethodNotSupportedException` | 405 (별도 code 없음) | 405 | Spring 기본 ProblemDetail에 위임 |
| 그 외 `Throwable` | `INTERNAL_ERROR` | 500 | 스택 트레이스는 로그에만 기록, 응답에 노출 X |

**405에 별도 도메인 코드를 정의하지 않는 이유**: HTTP method 오류는 도메인 규칙 위반이 아니라 프로토콜 레벨 오류다. 따라서 Spring의 기본 ProblemDetail 처리를 그대로 사용하고, 도메인 `ErrorCode`에는 405 항목을 두지 않는다.

### 5.3 DB 제약 위반 처리 범위

| 위반 종류 | 처리 |
|---|---|
| SQLState `23505` (unique violation) | constraint name으로 분기 → 5.4 표 참조 |
| SQLState `23503` (foreign key), `23502` (not null), `23514` (check) 등 그 외 DB 제약 위반 | 모두 `INTERNAL_ERROR` 500. 정상 흐름에서는 발생 불가 — 코드 버그 시그널이므로 로그에 상세 기록 |
| 입력 검증 실패 (음수, 길이 초과 등) | DB까지 가지 않도록 **Controller DTO Validation 또는 Service Validation에서 `INVALID_REQUEST`로 선차단** |

DB 제약은 어디까지나 **최종 안전망**이며, 정상 흐름의 입력 검증은 애플리케이션 레이어에서 끝나야 한다.

### 5.4 DataIntegrityViolationException 분기 (CONCURRENCY.md 9.5와 연결)

```java
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ProblemDetail> handleDataIntegrity(
        DataIntegrityViolationException e, HttpServletRequest req) {

    ErrorCode code = resolveByConstraintName(e);
    return buildProblem(code, code.defaultMessage(), req);
}

private ErrorCode resolveByConstraintName(DataIntegrityViolationException e) {
    String constraint = extractConstraintName(e);
    return switch (constraint) {
        case "uq_active_enrollment_per_user_course" -> ErrorCode.DUPLICATE_ENROLLMENT;
        case "uq_waiting_waitlist_per_user_course"  -> ErrorCode.DUPLICATE_WAITLIST;
        case "uq_enrollments_promoted_from_waitlist" -> ErrorCode.INTERNAL_ERROR;
        default -> ErrorCode.INTERNAL_ERROR;
    };
}
```

**`uq_enrollments_promoted_from_waitlist` 위반의 의미**: 이 제약 위반은 사용자 입력 충돌이 아니라 **자동 승격 로직의 중복 실행 또는 코드 버그**를 의미한다. 정상 동작에서는 발생할 수 없으므로 `INTERNAL_ERROR` 500으로 매핑하고, 로그에 constraint 이름을 남겨 디버깅 단서로 사용한다.

`extractConstraintName`은 PSQLException의 server error message에서 constraint 이름을 파싱하거나, `getMostSpecificCause()`를 캐스팅해 얻는다. 정확한 추출 코드는 구현 시 결정.

---

## 6. ProblemDetail 응답 변환

Spring Boot 3의 `ProblemDetail` (RFC 7807)을 그대로 사용하고, `code`를 확장 필드로 추가한다.

### 응답 형식

```json
{
  "type": "about:blank",
  "title": "Course Not Open",
  "status": 409,
  "detail": "강의가 모집 중 상태가 아닙니다.",
  "instance": "/api/courses/12/enrollments",
  "code": "COURSE_NOT_OPEN"
}
```

| 필드 | 출처 |
|---|---|
| `type` | `about:blank` 고정 (별도 type URI 운영 안 함) |
| `title` | `ErrorCode.title()` |
| `status` | `ErrorCode.status().value()` |
| `detail` | `DomainException.getMessage()` (customMessage 또는 defaultMessage) |
| `instance` | 요청 URI |
| `code` | `ErrorCode.name()` (확장 필드, `setProperty("code", ...)`) |

### Bean Validation 실패 응답 예시

```json
{
  "type": "about:blank",
  "title": "Invalid Request",
  "status": 400,
  "detail": "요청이 유효하지 않습니다.",
  "instance": "/api/courses",
  "code": "INVALID_REQUEST",
  "errors": [
    { "field": "title", "message": "1~100자여야 합니다." },
    { "field": "capacity", "message": "1 이상이어야 합니다." }
  ]
}
```

`errors` 배열을 추가 확장 필드로 두어 필드별 위반 정보를 노출.

---

## 7. 메시지 템플릿 정책

- 언어: **한국어** (i18n 미지원 — 과제 범위 외)
- 변수 치환은 `String.format` 또는 단순 문자열 결합으로 처리. `MessageFormat` 등 별도 도구 미사용
- 응답 메시지에는 **민감 정보 노출 금지** (예: 다른 사용자의 정보, 내부 ID 외 데이터)
- 메시지는 사용자가 직접 보는 문장 — 기술 용어 최소화

---

## 8. 클라이언트 분기 가이드

평가자/프론트엔드가 응답을 처리할 때 사용하는 우선순위:

1. **`code`** — 안정 식별자. 분기 처리에 사용. enum 이름 그대로(`COURSE_NOT_OPEN` 등).
2. **`status`** — HTTP 상태로 큰 범주 처리 (4xx vs 5xx).
3. **`detail`** — UI에 그대로 표시 가능한 한국어 메시지.
4. **`title`** — 사람용 분류 식별. 디버깅·로깅용.
5. **`instance`** — 어디서 발생했는지. 디버깅용.

`code`가 가장 안정적이며, `detail` 메시지는 변경될 수 있음.

---

## 9. 테스트 매트릭스

### 9.1 테스트 정책

각 `ErrorCode`는 가능한 경우 **통합 테스트**로 검증한다. `INTERNAL_ERROR`처럼 의도적 유도가 어려운 경우에는 `GlobalExceptionHandler` **단위 테스트**로 검증한다.

각 테스트는 응답 본문의 `code`, `status`, `title`이 카탈로그와 일치하는지를 함께 검증한다.

### 9.2 ErrorCode별 발생 시나리오

| code | 발생 시나리오 (테스트용) |
|---|---|
| `INVALID_REQUEST` | 강의 등록 시 `capacity=0` 같은 검증 실패 / 잘못된 enum 값을 query parameter로 전달 / page/size 범위 초과 |
| `UNAUTHENTICATED` | `X-USER-ID` 헤더 누락 / 존재하지 않는 user id 전달 |
| `FORBIDDEN` | STUDENT 사용자가 `POST /api/courses` 호출 |
| `NOT_COURSE_OWNER` | 다른 사용자의 강의에 `POST /open` |
| `NOT_ENROLLMENT_OWNER` | 다른 사용자의 enrollment에 `POST /confirm` |
| `NOT_WAITLIST_OWNER` | 다른 사용자의 waitlist에 `POST /cancel` |
| `COURSE_NOT_FOUND` | 존재하지 않는 course id로 상세 조회 |
| `ENROLLMENT_NOT_FOUND` | 존재하지 않는 enrollment id로 cancel |
| `WAITLIST_NOT_FOUND` | 존재하지 않는 waitlist id로 cancel |
| `INVALID_TRANSITION` | `DRAFT` 강의에 `POST /close` / `CLOSED` 강의에 `POST /open` |
| `COURSE_NOT_OPEN` | `DRAFT` 또는 `CLOSED` 강의에 수강 신청 |
| `DUPLICATE_ENROLLMENT` | 이미 활성 신청이 있는 사용자가 동일 강의 재신청 |
| `DUPLICATE_WAITLIST` | 이미 활성 대기가 있는 사용자가 동일 강의 재신청 |
| `INVALID_STATUS` (Enrollment) | 이미 `CANCELLED`인 enrollment에 `POST /confirm` |
| `INVALID_STATUS` (Waitlist) | 이미 `PROMOTED`/`CANCELLED`인 waitlist에 `POST /cancel` |
| `ALREADY_CANCELLED` | 이미 `CANCELLED`인 enrollment에 `POST /cancel` |
| `CANCEL_DEADLINE_EXCEEDED` | `Clock` mock으로 `confirmed_at + N+1일` 후로 시각 조정 후 cancel |
| `INTERNAL_ERROR` | (의도적 유도 어려움) — `GlobalExceptionHandler` 단위 테스트 (mock된 `Throwable` 입력으로 검증) |

### 9.3 DB Unique Violation 추가 검증

Partial unique index 위반은 서비스 검증을 우회한 repository/SQL 테스트 또는 동시 요청 통합 테스트로 검증한다.

| 검증 시나리오 | 기대 코드 |
|---|---|
| 서비스 검증 우회 → `uq_active_enrollment_per_user_course` 위반 | `DUPLICATE_ENROLLMENT` 409 |
| 서비스 검증 우회 → `uq_waiting_waitlist_per_user_course` 위반 | `DUPLICATE_WAITLIST` 409 |
| 자동 승격 로직 중복 실행 시뮬레이션 → `uq_enrollments_promoted_from_waitlist` 위반 | `INTERNAL_ERROR` 500 |

---

## 10. 구현 체크리스트

- [ ] `ErrorCode` enum 정의. 17개 상수 모두 포함. `HttpStatus` 타입 사용 (magic number 회피).
- [ ] `DomainException` 단일 클래스 정의 (`RuntimeException` 상속). 별도 `customMessage` 필드 없이 `super(message)` 위임.
- [ ] `GlobalExceptionHandler` (`@RestControllerAdvice`) 작성.
  - [ ] `DomainException` → ProblemDetail 변환 핸들러
  - [ ] `MethodArgumentNotValidException` → `INVALID_REQUEST` + `errors` 필드
  - [ ] `MethodArgumentTypeMismatchException`, `ConstraintViolationException`, `BindException`, `HttpMessageNotReadableException` → `INVALID_REQUEST`
  - [ ] `MissingRequestHeaderException` → `UNAUTHENTICATED`
  - [ ] `DataIntegrityViolationException` → constraint name 분기
  - [ ] `Throwable` → `INTERNAL_ERROR` (스택 트레이스 응답 노출 금지)
- [ ] 405는 별도 핸들러 두지 않고 Spring 기본 ProblemDetail 처리에 위임 (도메인 규칙 위반 아님).
- [ ] 도메인 서비스 코드에서 `throw ErrorCode.X.asException()` 또는 `throw new DomainException(ErrorCode.X)` 패턴만 사용. 일반 `RuntimeException` 도메인 위반 용도로 사용 금지.
- [ ] ProblemDetail 응답에 `code` 확장 필드를 빠짐없이 포함 (`setProperty("code", code.name())`).
- [ ] 각 `ErrorCode`에 대한 통합 테스트 케이스 작성 (9.2 매트릭스 기준). DB unique violation은 9.3 시나리오 추가.
- [ ] 응답 메시지에 민감 정보(다른 사용자 정보, 내부 스택 등) 노출되지 않는지 검토.
- [ ] `INTERNAL_ERROR` 발생 시 스택 트레이스는 로그에만, 응답에는 generic 메시지만.

---

## 11. 다른 문서와의 정합성

본 카탈로그가 SSoT이며, 다른 문서들은 다음 정보를 참조한다.

| 문서 | 이 카탈로그에서 가져가는 정보 |
|---|---|
| `docs/API.md` | 각 엔드포인트의 "주요 실패" 표의 `code` / HTTP |
| `docs/STATE_TRANSITIONS.md` | 각 전이의 "실패 매핑" 행의 `code` |
| `docs/CONCURRENCY.md` | 9.5 DB unique violation 매핑 표의 `code` |

본 카탈로그를 수정하면 위 세 문서의 관련 표도 함께 정합성을 맞춰야 한다.
