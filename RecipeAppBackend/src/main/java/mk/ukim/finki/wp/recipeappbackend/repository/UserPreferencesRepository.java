package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {
}
