package com.example.testproject.repository;

import com.example.testproject.model.WorldIsland;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorldIslandRepository extends JpaRepository<WorldIsland, Long> {

    List<WorldIsland> findAllByWorldId(Long worldId);
}
