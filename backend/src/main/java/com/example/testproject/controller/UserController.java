package com.example.testproject.controller;

import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.dto.UserDto;
import com.example.testproject.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/api/users")
    public List<UserDto> listUsers() {
        return userService.listUsers();
    }

    @PostMapping("/api/users")
    public UserDto createUser(@RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }
}
