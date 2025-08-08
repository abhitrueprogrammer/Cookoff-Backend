package com.uni.cookoff.dto.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CustomTestcaseRequest extends SubmissionRequest {
    private String customInput;
}