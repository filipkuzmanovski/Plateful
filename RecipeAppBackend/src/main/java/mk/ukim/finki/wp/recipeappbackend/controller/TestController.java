package mk.ukim.finki.wp.recipeappbackend.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {
    @GetMapping("/me")
    public String whoAmI(@AuthenticationPrincipal Jwt jwt){
        return jwt.getSubject();
    }
}
