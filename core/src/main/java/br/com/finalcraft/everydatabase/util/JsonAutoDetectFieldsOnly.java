package com.example.config.jackson;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes Jackson serialize/deserialize using ONLY the class fields,
 * completely ignoring getters, is-getters, and setters.
 *
 * <p>Useful for DTOs and transfer objects where you don't want the presence
 * (or absence) of getters/setters to influence the resulting JSON. With this,
 * the JSON contract reflects exactly the declared fields, with no surprises
 * coming from accessor methods — including "getX" methods that don't map to a
 * real field, which will no longer become JSON properties.</p>
 *
 * <p>Equivalent to annotating the class directly with:</p>
 * <pre>{@code
 * @JsonAutoDetect(
 *     fieldVisibility    = Visibility.ANY,   // all fields (including private) are detected
 *     getterVisibility   = Visibility.NONE,  // getters are ignored
 *     isGetterVisibility = Visibility.NONE,  // is-getters (boolean) are ignored
 *     setterVisibility   = Visibility.NONE   // setters are ignored
 * )
 * }</pre>
 *
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @JsonAutoDetectFieldsOnly
 * public class UserDTO {
 *     private String name;
 *     private int age;
 *
 *     // This getter does NOT become a "fullName" property in the JSON,
 *     // because getters are disabled.
 *     public String getFullName() {
 *         return name;
 *     }
 * }
 * }</pre>
 *
 * @see JsonAutoDetect
 * @see JacksonAnnotationsInside
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE}) // applicable to classes and to other annotations (composition)
@JacksonAnnotationsInside                               // tells Jackson to expand the nested annotations below
@JsonAutoDetect(
    fieldVisibility = Visibility.ANY,
    getterVisibility = Visibility.NONE,
    isGetterVisibility = Visibility.NONE,
    setterVisibility = Visibility.NONE
)
public @interface JsonAutoDetectFieldsOnly {

}
