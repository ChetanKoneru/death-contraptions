;;; helpers.el --- Reusable helpers for elisp-eval MCP  -*- lexical-binding: t; -*-

;; Loaded automatically on first elisp-eval call.
;; Prefer calling these over inlining equivalent code.

(defun eca--check-parens-file (file)
  "Check for unbalanced parens in FILE."
  (with-temp-buffer
    (insert-file-contents file)
    (emacs-lisp-mode)
    (condition-case err
        (progn (check-parens) "OK")
      (error (format "%s at pos %d" (error-message-string err) (point))))))

(defun eca--byte-compile-check (file)
  "Byte-compile FILE and return warnings/errors or OK."
  (let ((byte-compile-dest-file-function
         (lambda (_) (make-temp-file "eca-bytecomp-" nil ".elc"))))
    (with-temp-buffer
      (let ((byte-compile-log-buffer (buffer-name (current-buffer))))
        (byte-compile-file file)
        (let ((output (string-trim (buffer-string))))
          (if (string-empty-p output) "OK" output))))))

(defun eca--file-defuns (file)
  "List top-level definitions in FILE. Returns ((type . name) ...)."
  (with-temp-buffer
    (insert-file-contents file)
    (emacs-lisp-mode)
    (goto-char (point-min))
    (let (defs)
      (while (re-search-forward
              (rx bol "(" (group (or "defun" "defmacro" "defvar" "defcustom"
                                     "defconst" "cl-defun" "cl-defmacro"
                                     "cl-defmethod" "cl-defgeneric"
                                     "define-minor-mode" "define-derived-mode"
                                     "defclass" "defsubst"))
                  (+ space) (group (+ (not (any " \t\n()")))))
              nil t)
        (push (cons (match-string 1) (match-string 2)) defs))
      (nreverse defs))))

(provide 'eca-helpers)
