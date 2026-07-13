package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * No custom methods yet — lookups are by id (the Supabase auth user id,
 * pulled from the JWT "sub" claim), which findById() already covers.
 */
public interface UserRepository extends JpaRepository<User, UUID> {
}

