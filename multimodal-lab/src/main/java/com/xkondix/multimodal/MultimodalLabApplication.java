package com.xkondix.multimodal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Multimodal Lab — port 8089.
 *
 * The only module here that exists in ONE framework. Everywhere else the same
 * thing is built twice to compare LangChain4j and Spring AI; this is the
 * point where that comparison would stop being honest, because Spring AI has
 * ImageModel, TextToSpeechModel and TranscriptionModel as first-class
 * abstractions and LangChain4j does not have an equivalent audio story.
 *
 * "Where the frameworks stop being interchangeable" is a more useful slide
 * than a sixth side-by-side.
 */
@SpringBootApplication
public class MultimodalLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(MultimodalLabApplication.class, args);
    }
}
