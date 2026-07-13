package mk.ukim.finki.wp.recipeappbackend.model.entities;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps to public.users.
 * <p>
 * id is NOT generated here — it must equal the id of the corresponding row in
 * Supabase's auth.users (users.id REFERENCES auth.users(id)). Set it from the
 * JWT "sub" claim when creating this row, never generate a new one.
 * <p>
 * BUGFIX (review issue: assigned-id save semantics): because id is always
 * pre-set (never null), Spring Data's default "is it new?" heuristic said
 * "existing" for every save(), so repository.save(newUser) went through
 * em.merge() — an extra SELECT round trip, and an "already exists" would be
 * silently turned into an UPDATE instead of failing. Implementing
 * Persistable&lt;UUID&gt; with an explicit transient isNew flag restores proper
 * persist() semantics: freshly-built entities INSERT (and duplicate signups
 * fail loudly on the PK), while entities loaded from the DB update normally
 * (@PostLoad/@PostPersist flip the flag off).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements Persistable<UUID> {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // BUGFIX: not a column — tells Spring Data whether save() should
    // persist (INSERT) or merge (SELECT + UPDATE). Defaults to true for
    // newly constructed instances; flipped off once persisted or loaded.
    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
