package com.bonbon.backend.account.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.account.dto.AddressDtos;
import com.bonbon.backend.account.entity.Address;
import com.bonbon.backend.account.repository.AddressRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.geo.PlaceDetail;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's saved delivery addresses (manage-delivery-addresses.md): up to 10, exactly one default while
 * any exist. Goong Place Detail is called once per picked place, never when only the detail line changes.
 */
@Service
public class AddressService {

    static final int MAX_ADDRESSES = 10;

    private final AddressRepository addresses;
    private final Geocoder geocoder;

    AddressService(AddressRepository addresses, Geocoder geocoder) {
        this.addresses = addresses;
        this.geocoder = geocoder;
    }

    @Transactional(readOnly = true)
    public List<AddressDtos.View> list(UUID customerId) {
        return addresses.findForCustomer(customerId).stream().map(AddressDtos.View::of).toList();
    }

    @Transactional
    public AddressDtos.View create(UUID customerId, AddressDtos.Create req) {
        long existing = addresses.countByCustomerId(customerId);
        if (existing >= MAX_ADDRESSES) {
            throw new BusinessException(HttpStatus.CONFLICT, "ADDRESS_LIMIT_REACHED",
                    "Bạn đã lưu tối đa " + MAX_ADDRESSES + " địa chỉ. Hãy xoá bớt một địa chỉ.");
        }
        Address address = new Address(customerId);
        address.setLabel(req.label().strip());
        applyPlace(address, req.placeId());
        address.setDetail(blankToNull(req.detail()));
        address.setRecipientName(req.recipientName().strip());
        address.setRecipientPhone(req.recipientPhone());
        boolean makeDefault = existing == 0 || Boolean.TRUE.equals(req.makeDefault());
        if (makeDefault) {
            addresses.clearDefault(customerId);
        }
        address.setDefaultAddress(makeDefault);
        return AddressDtos.View.of(addresses.saveAndFlush(address));
    }

    @Transactional
    public AddressDtos.View update(UUID customerId, UUID id, AddressDtos.Update req) {
        Address address = find(customerId, id);
        if (req.label() != null) {
            address.setLabel(req.label().strip());
        }
        if (req.placeId() != null && !req.placeId().equals(address.getPlaceId())) {
            applyPlace(address, req.placeId());
        }
        if (req.detail() != null) {
            address.setDetail(blankToNull(req.detail()));
        }
        if (req.recipientName() != null) {
            address.setRecipientName(req.recipientName().strip());
        }
        if (req.recipientPhone() != null) {
            address.setRecipientPhone(req.recipientPhone());
        }
        return AddressDtos.View.of(addresses.saveAndFlush(address));
    }

    /** Clearing the old default and setting the new one happen in one transaction (the index allows one). */
    @Transactional
    public AddressDtos.View makeDefault(UUID customerId, UUID id) {
        find(customerId, id);
        addresses.clearDefault(customerId);
        Address address = find(customerId, id);
        address.setDefaultAddress(true);
        return AddressDtos.View.of(addresses.saveAndFlush(address));
    }

    /** Deleting the default promotes the most recent remaining address, so a default exists while any do. */
    @Transactional
    public void delete(UUID customerId, UUID id) {
        Address address = find(customerId, id);
        boolean wasDefault = address.isDefaultAddress();
        addresses.delete(address);
        addresses.flush();
        if (wasDefault) {
            addresses.findForCustomer(customerId).stream().findFirst().ifPresent(next -> {
                next.setDefaultAddress(true);
                addresses.saveAndFlush(next);
            });
        }
    }

    private void applyPlace(Address address, String placeId) {
        PlaceDetail place = geocoder.placeDetail(placeId).orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                "ADDRESS_NOT_FOUND", "Không xác định được địa chỉ này. Hãy chọn lại một gợi ý."));
        address.setPlace(place.placeId(), place.formattedAddress(), place.ward(), place.province(), place.lat(), place.lng());
    }

    private Address find(UUID customerId, UUID id) {
        // Another customer's address answers exactly like a missing one.
        return addresses.findByIdAndCustomerId(id, customerId).orElseThrow(() -> BusinessException.notFound(
                "ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
