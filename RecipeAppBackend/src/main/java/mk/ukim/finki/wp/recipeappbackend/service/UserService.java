package mk.ukim.finki.wp.recipeappbackend.service;

import mk.ukim.finki.wp.recipeappbackend.model.createDTO.UserCreateDto;
import mk.ukim.finki.wp.recipeappbackend.model.displayDTO.UserDisplayDto;
import mk.ukim.finki.wp.recipeappbackend.exception.ResourceNotFoundException;
import mk.ukim.finki.wp.recipeappbackend.model.entities.User;
import mk.ukim.finki.wp.recipeappbackend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Profile management. Identity (id, email) always arrives as parameters
 * extracted from the verified JWT by the controller — never from a request
 * body (see UserCreateDto's BUGFIX note).
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Called after Supabase signup, when the frontend first hits our API.
     * Idempotent on purpose: if the profile already exists (double-click,
     * retried request), we return it instead of failing — creating a profile
     * twice is not an error worth surfacing to the user.
     */
    @Transactional
    public UserDisplayDto getOrCreate(UUID id, String email, UserCreateDto dto) {
        User user = userRepository.findById(id)
                .orElseGet(() -> userRepository.save(dto.toEntity(id, email)));
        return UserDisplayDto.fromEntity(user);
    }

    @Transactional(readOnly = true)
    public UserDisplayDto getById(UUID id) {
        return userRepository.findById(id)
                .map(UserDisplayDto::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    /**
     * Update own profile names. No ownership check needed — the id IS the
     * authenticated user's id from the JWT, so you can only ever hit your
     * own row.
     */
    @Transactional
    public UserDisplayDto updateNames(UUID id, String firstName, String lastName) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return UserDisplayDto.fromEntity(user); // dirty checking flushes the UPDATE
    }
}
