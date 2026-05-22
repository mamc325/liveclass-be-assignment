# API 계약: 수강 신청 시스템

이 문서는 압축된 Spec Kit API 계약이다. 상세한 API 단일 진실 원천은 `docs/API.md`다.

## 공통

- 기준 URL: `/api`
- 필수 헤더: `X-USER-ID`
- Content-Type: `application/json; charset=UTF-8`
- 성공 응답은 공통 래퍼 없이 DTO를 직접 반환한다.
- 에러 응답은 RFC 7807 `ProblemDetail`에 `code`를 추가한다.

## 엔드포인트

| 메서드 | 경로 | 권한 | 상태 |
|---|---|---|---|
| POST | `/api/courses` | CREATOR | 구현 완료 |
| POST | `/api/courses/{id}/open` | CREATOR 본인 강의 | 구현 완료 |
| POST | `/api/courses/{id}/close` | CREATOR 본인 강의 | 구현 완료 |
| GET | `/api/courses` | 인증 사용자 | 구현 완료 |
| GET | `/api/courses/{id}` | 인증 사용자 | 구현 완료 |
| POST | `/api/courses/{id}/enrollments` | STUDENT | 컨트롤러 미구현 |
| POST | `/api/enrollments/{id}/confirm` | STUDENT 본인 신청 | 컨트롤러 미구현 |
| POST | `/api/enrollments/{id}/cancel` | STUDENT 본인 신청 | 컨트롤러 미구현 |
| GET | `/api/me/enrollments` | STUDENT | 컨트롤러 미구현 |
| POST | `/api/waitlists/{id}/cancel` | STUDENT 본인 대기 | 컨트롤러 미구현 |
| GET | `/api/me/waitlists` | STUDENT | 컨트롤러 미구현 |
| GET | `/api/courses/{id}/enrollments` | CREATOR 본인 강의 | 구현 완료 |
| GET | `/api/courses/{id}/waitlists` | CREATOR 본인 강의 | 구현 완료 |

## 에러 형식

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

## 안정적인 에러 코드

- `INVALID_REQUEST`
- `UNAUTHENTICATED`
- `FORBIDDEN`
- `NOT_COURSE_OWNER`
- `NOT_ENROLLMENT_OWNER`
- `NOT_WAITLIST_OWNER`
- `COURSE_NOT_FOUND`
- `ENROLLMENT_NOT_FOUND`
- `WAITLIST_NOT_FOUND`
- `INVALID_TRANSITION`
- `COURSE_NOT_OPEN`
- `DUPLICATE_ENROLLMENT`
- `DUPLICATE_WAITLIST`
- `INVALID_STATUS`
- `ALREADY_CANCELLED`
- `CANCEL_DEADLINE_EXCEEDED`
- `INTERNAL_ERROR`
