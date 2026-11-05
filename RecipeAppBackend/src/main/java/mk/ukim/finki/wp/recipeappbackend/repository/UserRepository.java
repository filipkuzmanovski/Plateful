package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Lookups are by id (the Supabase auth user id, pulled from the JWT "sub" claim), which findById()
 * already covers; the one custom query reads the admin flag without loading the entity.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** True only when the user exists and users.is_admin is set; false for an unknown id. */
    @Query("select count(u) > 0 from User u where u.id = :id and u.isAdmin = true")
    boolean isAdmin(@Param("id") UUID id);

    /** Locks the user's row for the transaction, so per-user limits stay safe when requests arrive together. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findForUpdateById(UUID id);
}

