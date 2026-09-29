package com.example.testproject.repository;

import com.example.testproject.model.PlayerPresence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerPresenceRepository extends JpaRepository<PlayerPresence, Long> {

    Optional<PlayerPresence> findByWorldIdAndUserId(Long worldId, Long userId);

    List<PlayerPresence> findAllByWorldId(Long worldId);
}
