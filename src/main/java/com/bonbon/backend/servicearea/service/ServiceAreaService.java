package com.bonbon.backend.servicearea.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.GeoDistance;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.servicearea.ServiceAreas;
import com.bonbon.backend.servicearea.dto.ServiceAreaRequests;
import com.bonbon.backend.servicearea.dto.ServiceAreaView;
import com.bonbon.backend.servicearea.entity.ServiceArea;
import com.bonbon.backend.servicearea.repository.ServiceAreaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The areas administrators curate, and the lookup the shop wizard uses to place a shop in one. */
@Service
public class ServiceAreaService implements ServiceAreas {

    private final ServiceAreaRepository areas;

    ServiceAreaService(ServiceAreaRepository areas) {
        this.areas = areas;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Area> locate(double lat, double lng) {
        return areas.findByActiveTrue().stream()
                .map(a -> new Candidate(a, GeoDistance.haversineKm(lat, lng, a.getCenterLat(), a.getCenterLng())))
                .filter(c -> c.distanceKm <= c.area.getRadiusKm().doubleValue())
                .min(Comparator.comparingDouble(c -> c.distanceKm))
                .map(c -> toArea(c.area));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Area> find(UUID id) {
        return id == null ? Optional.empty() : areas.findById(id).map(ServiceAreaService::toArea);
    }

    @Transactional(readOnly = true)
    public List<ServiceAreaView> list() {
        return areas.findAllByName().stream().map(ServiceAreaService::toView).toList();
    }

    @Transactional
    public ServiceAreaView create(CurrentPrincipal admin, ServiceAreaRequests.Save req) {
        requireFreeName(req.name(), new UUID(0, 0));
        ServiceArea area = new ServiceArea(req.name().strip(), req.centerLat(), req.centerLng(), req.radiusKm(),
                req.maxDeliveryRadiusKm(), admin.id());
        area.update(req.name().strip(), req.centerLat(), req.centerLng(), req.radiusKm(), req.maxDeliveryRadiusKm(),
                req.active() == null || req.active());
        return toView(areas.save(area));
    }

    /** The new limit applies to the next time a shop in the area saves its radius; shops already above it are left as they are. */
    @Transactional
    public ServiceAreaView update(UUID id, ServiceAreaRequests.Save req) {
        ServiceArea area = areas.findById(id)
                .orElseThrow(() -> BusinessException.notFound("SERVICE_AREA_NOT_FOUND", "Không có khu vực phục vụ này."));
        requireFreeName(req.name(), id);
        area.update(req.name().strip(), req.centerLat(), req.centerLng(), req.radiusKm(), req.maxDeliveryRadiusKm(),
                req.active() == null ? area.isActive() : req.active());
        return toView(areas.save(area));
    }

    private void requireFreeName(String name, UUID exceptId) {
        if (areas.nameTaken(name, exceptId)) {
            throw BusinessException.conflict("SERVICE_AREA_NAME_TAKEN", "Đã có khu vực phục vụ trùng tên.");
        }
    }

    private static Area toArea(ServiceArea a) {
        return new Area(a.getId(), a.getName(), a.getMaxDeliveryRadiusKm(), a.isActive());
    }

    private static ServiceAreaView toView(ServiceArea a) {
        return new ServiceAreaView(a.getId(), a.getName(), a.getCenterLat(), a.getCenterLng(), a.getRadiusKm(),
                a.getMaxDeliveryRadiusKm(), a.isActive(), a.getUpdatedAt());
    }

    private record Candidate(ServiceArea area, double distanceKm) {
    }
}
