package com.example.testproject.repository;

import com.example.testproject.model.PlayerResources;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerResourcesRepository extends JpaRepository<PlayerResources, Long> {

    Optional<PlayerResources> findByWorldIdAndUserId(Long worldId, Long userId);

    List<PlayerResources> findAllByWorldId(Long worldId);
}
