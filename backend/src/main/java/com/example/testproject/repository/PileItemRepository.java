package com.example.testproject.repository;

import com.example.testproject.model.PileItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PileItemRepository extends JpaRepository<PileItem, Long> {

    List<PileItem> findAllByNodeId(Long nodeId);
}
