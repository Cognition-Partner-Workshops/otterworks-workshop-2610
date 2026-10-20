package proxy

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/rs/zerolog"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/Cognition-Partner-Workshops/otterworks/services/api-gateway/internal/middleware"
)

const routerTestSecret = "router-test-secret"

// notification-service answers 400 without X-User-ID, so the gateway must derive
// it from the verified JWT on the bell's unread-count and list calls.
func TestRouter_ForwardsUserIDToNotificationService(t *testing.T) {
	type seen struct{ path, query, userID string }
	got := make(chan seen, 1)
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got <- seen{r.URL.Path, r.URL.RawQuery, r.Header.Get("X-User-ID")}
		w.WriteHeader(http.StatusOK)
	}))
	defer backend.Close()

	prefix := "/api/v1/notifications"
	handler := middleware.JWTAuth(middleware.JWTConfig{
		Secret:              routerTestSecret,
		ProtectedPrefixPath: []string{prefix},
	})(NewRouter(RouterConfig{
		Routes:    []Route{{Prefix: prefix, TargetURL: backend.URL}},
		CBManager: NewCircuitBreakerManager(defaultTestConfig()),
		Logger:    zerolog.Nop(),
	}))

	userID := "11111111-2222-3333-4444-555555555555"
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, middleware.JWTClaims{
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID,
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	}).SignedString([]byte(routerTestSecret))
	require.NoError(t, err)

	for _, target := range []string{
		"/api/v1/notifications/unread-count",
		"/api/v1/notifications?page=1&pageSize=20",
	} {
		t.Run(target, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, target, nil)
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("X-User-ID", "spoofed-user")
			rec := httptest.NewRecorder()

			handler.ServeHTTP(rec, req)

			require.Equal(t, http.StatusOK, rec.Code)
			s := <-got
			assert.Equal(t, userID, s.userID)
			assert.Equal(t, req.URL.Path, s.path)
			assert.Equal(t, req.URL.RawQuery, s.query)
		})
	}
}
