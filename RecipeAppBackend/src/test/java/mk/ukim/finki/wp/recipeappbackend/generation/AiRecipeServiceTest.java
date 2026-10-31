package mk.ukim.finki.wp.recipeappbackend.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.GeneratedRecipeSummaryDto;
import mk.ukim.finki.wp.recipeappbackend.model.entities.GeneratedRecipe;
import mk.ukim.finki.wp.recipeappbackend.repository.GeneratedRecipeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class AiRecipeServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final GeneratedRecipeRepository repository = mock(GeneratedRecipeRepository.class);
    private final GeneratedRecipeMapper mapper = mock(GeneratedRecipeMapper.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-31T10:00:00Z"));
    private final AiRecipeService service = new AiRecipeService(repository, mapper, clock);

    private static GeneratedRecipe row(OffsetDateTime savedAt) {
        return GeneratedRecipe.builder().id(ID).kind("cook").title("Egg fried rice").savedAt(savedAt).build();
    }

    @Test
    void get_otherUser_404() {
        when(repository.findByIdAndUserId(ID, OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OTHER, ID)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository).findByIdAndUserId(ID, OTHER);
    }

    @Test
    void get_owner_returnsTheMappedRecipe() {
        GeneratedRecipe row = row(null);
        GeneratedRecipeDto dto = mock(GeneratedRecipeDto.class);
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(row));
        when(mapper.toDto(row)).thenReturn(dto);

        assertThat(service.get(USER, ID)).isSameAs(dto);
    }

    @Test
    void save_setsSavedAtNow() {
        GeneratedRecipe row = row(null);
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(row));

        service.save(USER, ID);

        assertThat(row.getSavedAt()).isEqualTo(OffsetDateTime.parse("2026-10-31T10:00:00Z"));
        verify(repository).save(row);
    }

    @Test
    void save_twice_keepsFirstSavedAt() {
        GeneratedRecipe row = row(null);
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(row));

        service.save(USER, ID);
        OffsetDateTime first = row.getSavedAt();
        clock.set(Instant.parse("2026-10-31T18:00:00Z"));
        service.save(USER, ID);

        assertThat(row.getSavedAt()).isEqualTo(first);
        verify(repository).save(row); // only the first call wrote
    }

    @Test
    void save_otherUser_404AndNothingWritten() {
        when(repository.findByIdAndUserId(ID, OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(OTHER, ID)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void unsave_clears() {
        GeneratedRecipe row = row(OffsetDateTime.parse("2026-10-30T09:00:00Z"));
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(row));

        service.unsave(USER, ID);

        assertThat(row.getSavedAt()).isNull();
        verify(repository).save(row);
    }

    @Test
    void unsave_whenNotSaved_isANoOp() {
        GeneratedRecipe row = row(null);
        when(repository.findByIdAndUserId(ID, USER)).thenReturn(Optional.of(row));

        service.unsave(USER, ID);

        assertThat(row.getSavedAt()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void unsave_otherUser_404() {
        when(repository.findByIdAndUserId(ID, OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unsave(OTHER, ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void saved_sortedBySavedAtDesc_whateverTheClientAsked() {
        GeneratedRecipe a = row(OffsetDateTime.parse("2026-10-31T09:00:00Z"));
        GeneratedRecipeSummaryDto summary = new GeneratedRecipeSummaryDto(ID, "cook", null, "Egg fried rice", "d",
                15, a.getSavedAt());
        when(repository.findByUserIdAndSavedAtIsNotNull(any(), any()))
                .thenReturn(new PageImpl<>(List.of(a), PageRequest.of(2, 20), 41));
        when(mapper.toSummary(a)).thenReturn(summary);
        Pageable clients = PageRequest.of(2, 20, Sort.by(Sort.Direction.ASC, "title"));

        Page<GeneratedRecipeSummaryDto> page = service.saved(USER, clients);

        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdAndSavedAtIsNotNull(any(), sent.capture());
        assertThat(sent.getValue().getPageNumber()).isEqualTo(2);
        assertThat(sent.getValue().getPageSize()).isEqualTo(20);
        assertThat(sent.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "savedAt"));
        assertThat(page.getContent()).containsExactly(summary);
        assertThat(page.getTotalElements()).isEqualTo(41);
    }

    /** A clock the test can move, to prove a second save does not overwrite the first time. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
