package com.kama.mindagent.exception;

import com.kama.mindagent.model.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    /**
     * 捕获业务异常，错误信息返回给前端
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("业务处理失败: {}", e.getMessage(), e);
            return error(e.getStatus(), "服务器内部错误");
        }
        return error(e.getStatus(), e.getMessage());
    }

    /**
     * 处理 Bean Validation 校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("请求参数不合法");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 处理请求体、查询参数和 multipart 参数错误。
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        String message;
        if (e instanceof MissingServletRequestParameterException exception) {
            message = "缺少请求参数: " + exception.getParameterName();
        } else if (e instanceof MissingServletRequestPartException exception) {
            message = "缺少请求分片: " + exception.getRequestPartName();
        } else if (e instanceof MethodArgumentTypeMismatchException exception) {
            message = "请求参数类型错误: " + exception.getName();
        } else {
            message = "请求参数格式错误";
        }
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 处理 404 错误
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handle404(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "请求资源不存在");
    }

    /**
     * 捕获所有未处理的异常, 对前端不返回错误信息
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("Spring MVC 请求处理失败", e);
            }
            return error(status, frameworkErrorMessage(status));
        }
        log.error("服务器内部错误", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }

    private String frameworkErrorMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 405 -> "请求方法不支持";
            case 406 -> "无法生成客户端可接受的响应";
            case 413 -> "上传内容过大";
            case 415 -> "不支持的媒体类型";
            default -> status.is4xxClientError() ? "请求不合法" : "服务器内部错误";
        };
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status)
                .body(ApiResponse.error(status.value(), message));
    }
}
