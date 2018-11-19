grammar ArrayInit;

init : '{' value (','value)* '}' ; // 必须匹配至少一个value

value : init 
	  | INT
	  ;

INT : [0-9]+ ;
WS  : [\t\r\n]+ -> skip ;
