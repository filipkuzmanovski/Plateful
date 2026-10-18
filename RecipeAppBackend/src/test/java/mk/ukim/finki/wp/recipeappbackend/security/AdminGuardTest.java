package mk.ukim.finki.wp.recipeappbackend.security;

import mk.ukim.finki.wp.recipeappbackend.exception.AdminForbiddenException;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminGuardTest {

    private final UUID userId = UUID.randomUUID();
    private UserRepository userRepository;
    private MfaFactorLookup lookup;
    private AdminGuard guard;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        lookup = mock(MfaFactorLookup.class);
        guard = new AdminGuard(new RecipeVisibility(userRepository), lookup);
    }

    @Test
    void nonAdminIsForbiddenWithoutAskingAboutFactors() {
        when(userRepository.isAdmin(userId)).thenReturn(false);

        assertThatThrownBy(() -> guard.requireAdmin(userId))
                .isInstanceOfSatisfying(AdminForbiddenException.class,
                        e -> assertThat(e.getCode()).isEqualTo("forbidden"));
        verifyNoInteractions(lookup);
    }

    @Test
    void adminWithoutFactorMustSetOneUp() {
        when(userRepository.isAdmin(userId)).thenReturn(true);
        when(lookup.hasVerifiedFactor(userId)).thenReturn(false);

        assertThatThrownBy(() -> guard.requireAdmin(userId))
                .isInstanceOfSatisfying(AdminForbiddenException.class,
                        e -> assertThat(e.getCode()).isEqualTo("admin_mfa_setup_required"));
    }

    @Test
    void adminWithFactorPasses() {
        when(userRepository.isAdmin(userId)).thenReturn(true);
        when(lookup.hasVerifiedFactor(userId)).thenReturn(true);

        assertThatCode(() -> guard.requireAdmin(userId)).doesNotThrowAnyException();
    }

    @Test
    void factorLookupFailureFailsClosed() {
        when(userRepository.isAdmin(userId)).thenReturn(true);
        when(lookup.hasVerifiedFactor(userId)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> guard.requireAdmin(userId)).isInstanceOf(IllegalStateException.class);
    }
}
