package com.example.testproject.repository;

import com.example.testproject.model.ResourceNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceNodeRepository extends JpaRepository<ResourceNode, Long> {

    List<ResourceNode> findAllByWorldId(Long worldId);

    boolean existsByWorldId(Long worldId);
}
