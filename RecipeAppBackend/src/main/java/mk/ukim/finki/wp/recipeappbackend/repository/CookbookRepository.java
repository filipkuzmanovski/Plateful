package mk.ukim.finki.wp.recipeappbackend.repository;

import mk.ukim.finki.wp.recipeappbackend.model.entities.Cookbook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CookbookRepository extends JpaRepository<Cookbook, UUID> {

    List<Cookbook> findByUserIdOrderByVolumeDesc(UUID userId);

    long countByUserId(UUID userId);

    /** Zero when the user has no cookbooks. */
    @Query("SELECT COALESCE(MAX(c.volume), 0) FROM Cookbook c WHERE c.userId = :userId")
    int findMaxVolume(@Param("userId") UUID userId);

    Optional<Cookbook> findByIdAndUserId(UUID id, UUID userId);
}
