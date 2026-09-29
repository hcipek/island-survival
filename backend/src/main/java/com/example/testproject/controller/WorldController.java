package com.example.testproject.controller;

import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.IslandLocationDto;
import com.example.testproject.model.dto.PlayerPresenceDto;
import com.example.testproject.model.dto.WorldDto;
import com.example.testproject.model.dto.WorldStateDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.model.req.UpdatePositionRequest;
import com.example.testproject.service.WorldService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WorldController {

    private final WorldService worldService;

    public WorldController(WorldService worldService) {
        this.worldService = worldService;
    }

    @GetMapping("/api/worlds")
    public List<WorldDto> listWorlds() {
        return worldService.listWorlds();
    }

    @PostMapping("/api/worlds")
    public WorldDto createWorld(@RequestBody CreateWorldRequest request) {
        return worldService.createWorld(request);
    }

    @DeleteMapping("/api/worlds/{worldId}")
    public void deleteWorld(@PathVariable long worldId) {
        worldService.deleteWorld(worldId);
    }

    @GetMapping("/api/worlds/{worldId}/islands")
    public List<IslandLocationDto> getIslands(@PathVariable long worldId) {
        return worldService.getIslands(worldId);
    }

    @GetMapping("/api/worlds/{worldId}/state")
    public WorldStateDto getState(@PathVariable long worldId) {
        return worldService.getState(worldId);
    }

    @GetMapping("/api/worlds/{worldId}/buildings")
    public List<BuildingDto> getBuildings(@PathVariable long worldId) {
        return worldService.getBuildings(worldId);
    }

    @PostMapping("/api/worlds/{worldId}/buildings")
    public BuildingDto createBuilding(@PathVariable long worldId, @RequestBody BuildingRequest request) {
        return worldService.createBuilding(worldId, request);
    }

    @DeleteMapping("/api/worlds/{worldId}/buildings/{buildingId}")
    public void deleteBuilding(
            @PathVariable long worldId,
            @PathVariable long buildingId,
            @RequestParam long userId
    ) {
        worldService.deleteBuilding(worldId, buildingId, userId);
    }

    @PutMapping("/api/worlds/{worldId}/players/{userId}/position")
    public void updatePlayerPosition(
            @PathVariable long worldId,
            @PathVariable long userId,
            @RequestBody UpdatePositionRequest request
    ) {
        worldService.updatePlayerPosition(worldId, userId, request);
    }

    @GetMapping("/api/worlds/{worldId}/players")
    public List<PlayerPresenceDto> getPlayers(
            @PathVariable long worldId,
            @RequestParam long excludeUserId
    ) {
        return worldService.getPlayers(worldId, excludeUserId);
    }
}
