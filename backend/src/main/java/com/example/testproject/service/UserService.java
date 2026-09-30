package com.example.testproject.service;

import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.live.WorldSessionRegistry;
import com.example.testproject.model.User;
import com.example.testproject.model.dto.UserDto;
import com.example.testproject.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.Random;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final WorldSessionRegistry sessionRegistry;
    private final Random random = new Random();

    public UserService(UserRepository userRepository, WorldSessionRegistry sessionRegistry) {
        this.userRepository = userRepository;
        this.sessionRegistry = sessionRegistry;
    }

    public List<UserDto> listUsers() {
        Set<Long> playing = sessionRegistry.playingUserIds();
        return userRepository.findAll().stream()
                .map(user -> UserDto.from(user, playing.contains(user.getId())))
                .toList();
    }

    public UserDto createUser(CreateUserRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty");
        }
        if (userRepository.findFirstByNameIgnoreCase(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is already taken");
        }
        return UserDto.from(userRepository.save(new User(name, randomColor())), false);
    }

    /// A random but consistently vivid/readable color (fixed saturation and
    /// brightness, random hue) so player colors are distinct without any
    /// chance of landing on something too dark or washed out to see.
    private String randomColor() {
        int rgb = java.awt.Color.HSBtoRGB(random.nextFloat(), 0.65f, 0.85f) & 0xFFFFFF;
        return String.format("#%06X", rgb);
    }
}
