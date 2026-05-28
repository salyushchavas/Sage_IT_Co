package com.spire.backend.service;

import com.spire.backend.dto.CreateModuleRequest;
import com.spire.backend.dto.ModuleDTO;
import com.spire.backend.dto.UpdateModuleRequest;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.Module;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.CourseRepository;
import com.spire.backend.repository.ModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModuleService {

    private final ModuleRepository moduleRepository;
    private final CourseRepository courseRepository;

    @Transactional(readOnly = true)
    public List<ModuleDTO> getModulesByCourse(Long courseId, boolean includeVideoUrls) {
        return moduleRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .map(m -> ModuleDTO.from(m, includeVideoUrls))
                .collect(Collectors.toList());
    }

    @Transactional
    public ModuleDTO createModule(Long courseId, CreateModuleRequest dto, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only add modules to your own courses");
        }

        int nextOrder = dto.getOrderIndex() != null
                ? dto.getOrderIndex()
                : moduleRepository.findByCourseIdOrderByOrderIndexAsc(courseId).size();

        Module module = Module.builder()
                .course(course)
                .title(dto.getTitle())
                .description(dto.getDescription())
                .orderIndex(nextOrder)
                .build();

        return ModuleDTO.from(moduleRepository.save(module), true);
    }

    @Transactional
    public ModuleDTO updateModule(Long moduleId, UpdateModuleRequest dto, Long userId, boolean isAdmin) {
        Module module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Module", "id", moduleId));

        if (!isAdmin && !module.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only edit modules in your own courses");
        }

        if (dto.getTitle() != null) module.setTitle(dto.getTitle());
        if (dto.getDescription() != null) module.setDescription(dto.getDescription());
        if (dto.getOrderIndex() != null) module.setOrderIndex(dto.getOrderIndex());

        return ModuleDTO.from(moduleRepository.save(module), true);
    }

    @Transactional
    public void deleteModule(Long moduleId, Long userId, boolean isAdmin) {
        Module module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Module", "id", moduleId));

        if (!isAdmin && !module.getCourse().getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only delete modules in your own courses");
        }

        moduleRepository.delete(module);
    }

    @Transactional
    public List<ModuleDTO> reorderModules(Long courseId, List<Long> orderedModuleIds, Long userId, boolean isAdmin) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        if (!isAdmin && !course.getInstructor().getId().equals(userId)) {
            throw new UnauthorizedException("You can only reorder modules in your own courses");
        }

        List<Module> existing = moduleRepository.findByCourseIdOrderByOrderIndexAsc(courseId);

        // Validate all provided IDs belong to this course.
        for (Long id : orderedModuleIds) {
            if (existing.stream().noneMatch(m -> m.getId().equals(id))) {
                throw new ResourceNotFoundException("Module", "id", id);
            }
        }

        for (int i = 0; i < orderedModuleIds.size(); i++) {
            Long id = orderedModuleIds.get(i);
            Module m = existing.stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
            m.setOrderIndex(i);
            moduleRepository.save(m);
        }

        return moduleRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .map(m -> ModuleDTO.from(m, true))
                .collect(Collectors.toList());
    }
}
