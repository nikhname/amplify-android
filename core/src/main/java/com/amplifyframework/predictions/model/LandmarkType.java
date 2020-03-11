/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.predictions.model;

/**
 * Types of detectable landmark.
 */
@SuppressWarnings("JavadocVariable")
public enum LandmarkType {    
    ALL_POINTS,
    LEFT_EYE,
    RIGHT_EYE,
    LEFT_EYEBROW,
    RIGHT_EYEBROW,
    NOSE,
    NOSE_CREST,
    MEDIAN_LINE,
    OUTER_LIPS,
    INNER_LIPS,
    LEFT_PUPIL,
    RIGHT_PUPIL,
    FACE_CONTOUR
}
