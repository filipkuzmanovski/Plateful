package mk.ukim.finki.wp.recipeappbackend.model.entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mk.ukim.finki.wp.recipeappbackend.model.InstructionStep;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to public.recipes.
 * <p>
 * NOTE on `cuisines` (text[]): mapped via Hibernate's native
 * @JdbcTypeCode(SqlTypes.ARRAY) support for Postgres array columns. This is
 * the one mapping in this file I'd flag as worth double-checking first if
 * anything throws on startup — Postgres array support in JPA/Hibernate has a
 * messier history than the rest of these mappings, so if this specific field
 * errors, that's the first place to look.
 */
@Entity
@Table(name = "recipes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Builder.Default
    @Column(name = "source", nullable = false)
    private String source = "user";

    @Column(name = "spoonacular_id")
    private Long spoonacularId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "image", columnDefinition = "text")
    private String image;

    @Column(name = "servings")
    private Integer servings;

    @Column(name = "ready_in_minutes")
    private Integer readyInMinutes;

    @Column(name = "cooking_minutes")
    private Integer cookingMinutes;

    @Column(name = "preparation_minutes")
    private Integer preparationMinutes;

    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "instructions", columnDefinition = "text")
    private String instructions;

    // BUGFIX (review issue #1): was List<String>, but the JSONB column stores
    // Spoonacular's analyzedInstructions objects [{number, step, ingredients[],
    // equipment[]}]. Reading an imported recipe back would have blown up at
    // deserialization time. Now mapped to the shared InstructionStep record —
    // same shape in entity, create DTO and display DTO.
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    @Column(name = "instructions_steps", nullable = false, columnDefinition = "jsonb")
    private List<InstructionStep> instructionsSteps = List.of();

    @Builder.Default
    @Column(name = "dairy_free", nullable = false)
    private boolean dairyFree = false;

    @Builder.Default
    @Column(name = "gluten_free", nullable = false)
    private boolean glutenFree = false;

    @Builder.Default
    @Column(name = "vegan", nullable = false)
    private boolean vegan = false;

    @Builder.Default
    @Column(name = "vegetarian", nullable = false)
    private boolean vegetarian = false;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(name = "cuisines", nullable = false, columnDefinition = "text[]")
    private List<String> cuisines = List.of();

    @Column(name = "calories", precision = 7, scale = 2)
    private BigDecimal calories;

    @Column(name = "protein_grams", precision = 7, scale = 2)
    private BigDecimal proteinGrams;

    @Column(name = "fat_grams", precision = 7, scale = 2)
    private BigDecimal fatGrams;

    @Column(name = "carbs_grams", precision = 7, scale = 2)
    private BigDecimal carbsGrams;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nutrition", columnDefinition = "jsonb")
    private Map<String, Object> nutrition;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
