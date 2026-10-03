package com.bonbon.backend.merchant.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/** Wizard step 3, tax part: what an e-invoice and the tax authority need, by business model. */
@Entity
@Table(name = "vendor_tax_info")
public class VendorTaxInfo {

    public enum BusinessType { INDIVIDUAL, HOUSEHOLD }

    public static final int MAX_INVOICE_EMAILS = 5;

    @Id
    @Column(name = "vendor_id")
    private UUID vendorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", columnDefinition = "text")
    private BusinessType businessType;

    @Column(name = "business_name", columnDefinition = "text")
    private String businessName;

    @Column(name = "business_address", columnDefinition = "text")
    private String businessAddress;

    @Column(name = "tax_code", columnDefinition = "text")
    private String taxCode;

    @Column(name = "license_file_key", columnDefinition = "text")
    private String licenseFileKey;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "vendor_invoice_emails", joinColumns = @JoinColumn(name = "vendor_id"))
    @OrderColumn(name = "position")
    @Column(name = "email", nullable = false, columnDefinition = "text")
    private List<String> invoiceEmails = new ArrayList<>();

    protected VendorTaxInfo() {
    }

    public VendorTaxInfo(UUID vendorId) {
        this.vendorId = vendorId;
    }

    public void update(BusinessType type, String businessName, String businessAddress, String taxCode, List<String> emails) {
        this.businessType = type;
        this.businessName = businessName;
        this.businessAddress = businessAddress;
        this.taxCode = taxCode;
        this.invoiceEmails.clear();
        this.invoiceEmails.addAll(emails);
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public BusinessType getBusinessType() {
        return businessType;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getBusinessAddress() {
        return businessAddress;
    }

    public String getTaxCode() {
        return taxCode;
    }

    public String getLicenseFileKey() {
        return licenseFileKey;
    }

    public void setLicenseFileKey(String licenseFileKey) {
        this.licenseFileKey = licenseFileKey;
    }

    public List<String> getInvoiceEmails() {
        return List.copyOf(invoiceEmails);
    }
}
